package com.dataweave.api.interfaces;

import com.dataweave.api.application.DataOpsBridge;
import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.Locales;
import com.dataweave.api.infrastructure.OpsMessages;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.api.interfaces.dto.*;
// 显式单类型导入消歧：dto.BackfillRun 与 master.domain.BackfillRun 同名，控制器用的是 dto（data-ops-center 集成）。
import com.dataweave.api.interfaces.dto.BackfillRun;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.OpsContracts;
import com.dataweave.master.application.OpsService;
import com.dataweave.master.application.ProjectScope;
import com.dataweave.master.application.RecoveryService;
import com.dataweave.master.application.SchedulerMetrics;
import com.dataweave.master.application.SchedulerMetrics.MetricsSnapshot;
import com.dataweave.master.application.SlaService;
import com.dataweave.master.application.WorkflowService;
import com.dataweave.master.application.WorkflowService.NodeTaskDetail;
import com.dataweave.master.domain.*;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.i18n.Messages;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 调度运维 / 驾驶舱查询 REST 端点：全局概况、任务定义、运行实例、失败清单、系统指标。
 *
 * <p>供前端驾驶舱首页（{@code /}）、调度运维页（{@code /ops}）、数据开发页（{@code /tasks}）拉取。
 * 读侧走 REST，写侧（任务定义写入）统一走 project_push（MCP）。
 *
 * <p>data-ops-center Stream C：新增周期实例筛选分页、批量操作、补数据、冻结端点；
 * 写操作全部经 {@link GatedActionService} 闸门 + agent_action 审计，无旁路。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final OpsService opsService;
    private final RecoveryService recoveryService;
    private final SchedulerMetrics metrics;
    private final SlaService slaService;
    private final LogBus logBus;
    private final LogArchiveStorage logArchive;
    private final EventBus eventBus;
    private final Messages messages;
    private final OpsMessages opsMessages;
    private final DataOpsBridge dataOpsBridge;
    private final GatedActionService gatedActionService;
    private final WorkflowService workflowService;
    private final ProjectScope projectScope;

    /** 当前活跃 SSE 长连接计数（logStream live 路径 + workflowEventsStream），供 scheduler.sse.connections Gauge。 */
    private final AtomicLong sseConnCount = new AtomicLong(0);

    public OpsController(OpsService opsService, RecoveryService recoveryService,
                         SchedulerMetrics metrics, SlaService slaService,
                         LogBus logBus, LogArchiveStorage logArchive, EventBus eventBus,
                         Messages messages, OpsMessages opsMessages,
                         DataOpsBridge dataOpsBridge,
                         GatedActionService gatedActionService,
                         WorkflowService workflowService,
                         ProjectScope projectScope) {
        this.opsService = opsService;
        this.recoveryService = recoveryService;
        this.metrics = metrics;
        this.slaService = slaService;
        this.logBus = logBus;
        this.logArchive = logArchive;
        this.eventBus = eventBus;
        this.messages = messages;
        this.opsMessages = opsMessages;
        this.dataOpsBridge = dataOpsBridge;
        this.gatedActionService = gatedActionService;
        this.workflowService = workflowService;
        this.projectScope = projectScope;
    }

    /**
     * 036 项目隔离：解析有效 projectId。
     * 优先使用请求显式参数，回退到 TenantContext.projectId()（由 JwtAuthFilter 从 X-Project-Id 头/查询参数注入），
     * 最后经 ProjectScope.require 校验成员归属。
     */
    private Long resolveProjectId(Long requestProjectId) {
        Long pid = requestProjectId != null ? requestProjectId : TenantContext.projectId();
        return projectScope.require(TenantContext.tenantId(), TenantContext.userId(), pid);
    }

    /** 驾驶舱全局态势：计数 + 失败实例清单 + Agent 诊断中事项。036 项目隔离：按当前项目收敛。 */
    @GetMapping("/summary")
    public ApiResponse<OpsService.DashboardSummary> summary(
            @RequestParam(required = false) Long projectId) {
        return ApiResponse.ok(opsService.summary(resolveProjectId(projectId)));
    }


    /**
     * 周期任务流列表（运维主体）：仅 ONLINE & schedule_type=CRON 的已发布工作流。
     * 支持名称模糊/草稿改动/最近触发结果/目录/创建人筛选 + 分页，返回 {@link Page} 信封。
     * 无任何筛选/分页参数时返回首页全量兼容（旧调用）。
     */
    @GetMapping("/periodic-workflows")
    public ApiResponse<?> periodicWorkflows(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer hasDraftChange,
            @RequestParam(required = false) String recentResult,
            @RequestParam(required = false) Long catalogNodeId,
            @RequestParam(required = false) Long createdBy,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return workflowPage("CRON", keyword, hasDraftChange, recentResult, catalogNodeId, createdBy, projectId, page, size,
                () -> opsService.periodicWorkflows(resolveProjectId(projectId)));
    }

    /**
     * 手动任务流列表（运维主体）：仅 ONLINE & schedule_type=MANUAL 的已发布工作流。
     * 支持名称模糊/最近触发结果/创建人/目录筛选 + 分页，返回 {@link Page} 信封。
     */
    @GetMapping("/manual-workflows")
    public ApiResponse<?> manualWorkflows(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String recentResult,
            @RequestParam(required = false) Long createdBy,
            @RequestParam(required = false) Long catalogNodeId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return workflowPage("MANUAL", keyword, null, recentResult, catalogNodeId, createdBy, projectId, page, size,
                () -> opsService.manualWorkflows(resolveProjectId(projectId)));
    }

    /**
     * 任务流列表筛选分页公共逻辑：统一走 {@link OpsService#queryWorkflows} 返回 {@link Page} 信封
     * （含 recentTriggerResult 衍生列，始终分页，避免首屏与筛选后列不一致）。
     * {@code legacy} 全量方法保留供其它只读引用，此处不再走它。
     */
    private ApiResponse<?> workflowPage(String scheduleType, String keyword, Integer hasDraftChange,
                                        String recentResult, Long catalogNodeId, Long createdBy,
                                        Long projectId, int page, int size,
                                        java.util.function.Supplier<List<WorkflowDef>> legacy) {
        Long pid = resolveProjectId(projectId);
        OpsContracts.PageResult<OpsContracts.WorkflowListRow> pr = opsService.queryWorkflows(
                new OpsContracts.WorkflowQuery(scheduleType, keyword, hasDraftChange, recentResult,
                        catalogNodeId, createdBy, pid, Math.max(0, page - 1), size));
        return ApiResponse.ok(new Page<>(pr.items(), pr.total(), page, size));
    }

    /**
     * 顶条「最迟看板 ETA」：当前运行中实例里最迟的预计完成时刻（基于历史时长中位数）。
     * 无运行中实例或冷启动无样本 → data=null，前端显示「估算中」。
     * 036 项目隔离：按当前项目收敛，不再硬编码 1/1。
     */
    @GetMapping("/eta-summary")
    public ApiResponse<SlaService.EtaResult> etaSummary(
            @RequestParam(required = false) Long projectId) {
        Long pid = resolveProjectId(projectId);
        return ApiResponse.ok(slaService.predictLatestEta(TenantContext.tenantId(), pid));
    }

    /**
     * 周期实例筛选分页查询 — 契约①。
     * 兼容旧调用（无参数时返回全部 NORMAL 实例列表）。
     *
     * <p><strong>租户隔离：</strong>当前租户/项目过滤由 Stream A 的 {@code DataOpsBridge} 真实现负责
     * （从安全上下文获取 {@code tenantId/projectId}），契约②冻结后由 A 侧统一注入。
     * 此端点本身不做租户解析——与现有 {@code /api/ops/instances}（无参数版）的隔离模型一致。
     */
    @GetMapping("/instances")
    public ApiResponse<?> instances(
            @RequestParam(required = false) String runMode,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String bizDate,
            @RequestParam(required = false) String stateIn,
            @RequestParam(required = false) String bizDateFrom,
            @RequestParam(required = false) String bizDateTo,
            @RequestParam(required = false) String startedAtFrom,
            @RequestParam(required = false) String startedAtTo,
            @RequestParam(required = false) String workerNodeCode,
            @RequestParam(required = false) String failureReason,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        // 统一走 dataOpsBridge，返回 Page<InstanceRow> 格式保证前端解析一致
        // 前端 1-indexed，后端 0-indexed，做转换；响应用原始 page 保持一致性
        int page0 = Math.max(0, page - 1);
        Long pid = resolveProjectId(projectId);
        InstanceQuery q = new InstanceQuery(runMode, state, taskId, bizDate,
                stateIn, bizDateFrom, bizDateTo, startedAtFrom, startedAtTo,
                workerNodeCode, failureReason, pid, page0, size);
        Page<InstanceRow> result = dataOpsBridge.queryInstances(q);
        return ApiResponse.ok(new Page<>(result.items(), result.total(), page, result.size()));
    }

    /**
     * 多维筛选 + 分页查询任务流实例列表。
     * page 从 1 起；size 上限 200。
     */
    @GetMapping("/workflow-instances")
    public ApiResponse<?> workflowInstances(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String stateIn,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) Long workflowId,
            @RequestParam(required = false) String bizDate,
            @RequestParam(required = false) String bizDateFrom,
            @RequestParam(required = false) String bizDateTo,
            @RequestParam(required = false) String startedAtFrom,
            @RequestParam(required = false) String startedAtTo,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 前端 1-indexed → 后端 0-indexed；响应用原始 page
        int page0 = Math.max(0, page - 1);
        Long pid = resolveProjectId(projectId);
        com.dataweave.master.application.OpsContracts.WorkflowInstanceQuery q =
                new com.dataweave.master.application.OpsContracts.WorkflowInstanceQuery(
                        state, stateIn, triggerType, workflowId, bizDate,
                        bizDateFrom, bizDateTo, startedAtFrom, startedAtTo,
                        pid, page0, size);
        var result = dataOpsBridge.queryWorkflowInstances(q);
        return ApiResponse.ok(new Page<>(result.items(), result.total(), page, result.size()));
    }

    /** 工作流实例详情（实例 + 其下任务节点）。 */
    @GetMapping("/workflow-instances/{id}")
    public ApiResponse<OpsService.WorkflowInstanceDetail> workflowInstance(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.workflowInstanceDetail(id));
    }

    /** 实例级 DAG 视图：历史拓扑（发布时快照）+ task_instance 运行时状态叠加。 */
    @GetMapping("/workflow-instances/{id}/dag")
    public ApiResponse<?> workflowInstanceDag(@PathVariable UUID id) {
        var dag = dataOpsBridge.getInstanceDag(id);
        if (dag == null) {
            return ApiResponse.err(404, "workflow.instance.not_found");
        }
        return ApiResponse.ok(dag);
    }

    /** 任务实例参数替换后的实际代码。 */
    @GetMapping("/task-instances/{id}/resolved-code")
    public ApiResponse<?> resolvedCode(@PathVariable UUID id) {
        var code = dataOpsBridge.getResolvedCode(id);
        if (code == null) {
            return ApiResponse.err(404, "task.instance.not_found");
        }
        return ApiResponse.ok(code);
    }

    /** 任务实例参数替换后的实际配置。 */
    @GetMapping("/task-instances/{id}/resolved-config")
    public ApiResponse<?> resolvedConfig(@PathVariable UUID id) {
        var config = dataOpsBridge.getResolvedConfig(id);
        if (config == null) {
            return ApiResponse.err(404, "task.instance.not_found");
        }
        return ApiResponse.ok(config);
    }

    /** 失败的正式运行实例。036 项目隔离：按当前项目过滤。 */
    @GetMapping("/failed")
    public ApiResponse<List<TaskInstance>> failed(
            @RequestParam(required = false) Long projectId) {
        return ApiResponse.ok(opsService.failedInstances(resolveProjectId(projectId)));
    }

    /**
     * 某任务定义的最近一个实例（默认 NORMAL，可 runMode=TEST），供前端重开/刷新续接运行态。
     * 无实例返回 data=null。仅查询，无写副作用。
     */
    @GetMapping("/tasks/{taskDefId}/latest-instance")
    public ApiResponse<OpsService.LatestInstanceView> latestTaskInstance(
            @PathVariable Long taskDefId,
            @RequestParam(required = false) String runMode) {
        return ApiResponse.ok(opsService.latestTaskInstance(taskDefId, runMode, resolveProjectId(null)));
    }

    /** 某工作流定义的最近一个实例，供前端续接；无实例返回 data=null。仅查询。 */
    @GetMapping("/workflows/{workflowId}/latest-instance")
    public ApiResponse<OpsService.LatestInstanceView> latestWorkflowInstance(@PathVariable Long workflowId) {
        return ApiResponse.ok(opsService.latestWorkflowInstance(workflowId, resolveProjectId(null)));
    }

    // ─── 实例生命周期操作 ─────────────────────────────────

    @PostMapping("/instances/{id}/pause")
    public ApiResponse<?> pause(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.pauseWorkflow(id));
    }

    @PostMapping("/instances/{id}/resume")
    public ApiResponse<?> resume(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.resumeWorkflow(id));
    }

    @PostMapping("/instances/{id}/kill")
    public ApiResponse<?> kill(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.killWorkflow(id));
    }

    @PostMapping("/instances/{id}/rerun")
    public ApiResponse<?> rerun(@PathVariable UUID id, ServerWebExchange exchange) {
        boolean ok = recoveryService.rerunAll(id);
        if (!ok) {
            throw new BizException("ops.rerun.no_effect");
        }
        var locale = Locales.uiLocale(exchange.getRequest().getHeaders());
        return ApiResponse.ok(messages.get("ops.rerun.triggered", locale));
    }

    @PostMapping("/instances/{id}/recover")
    public ApiResponse<?> recover(@PathVariable UUID id, ServerWebExchange exchange) {
        boolean ok = recoveryService.resume(id);
        if (!ok) {
            throw new BizException("ops.recover.no_effect");
        }
        var locale = Locales.uiLocale(exchange.getRequest().getHeaders());
        return ApiResponse.ok(messages.get("ops.recover.triggered", locale));
    }

    @PostMapping("/task-instances/{id}/pause")
    public ApiResponse<?> pauseTask(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.pauseTask(id));
    }

    @PostMapping("/task-instances/{id}/resume")
    public ApiResponse<?> resumeTask(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.resumeTask(id));
    }

    @PostMapping("/task-instances/{id}/kill")
    public ApiResponse<?> killTask(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.killTask(id));
    }

    @PostMapping("/task-instances/{id}/rerun")
    public ApiResponse<?> rerunTask(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.rerunInstance(id));
    }

    @PostMapping("/task-instances/{id}/set-success")
    public ApiResponse<?> setSuccess(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.setSuccess(id));
    }

    // ─── 契约① 新增端点：批量操作 / 补数据 / 冻结 ─────────────────

    /**
     * 批量操作：rerun / kill / set-success — 契约①。
     * 逐个经闸门裁决，返回每项结果 + outcome。
     */
    @PostMapping("/instances/batch")
    public ApiResponse<Map<String, Object>> batchOp(@RequestBody Map<String, Object> body,
                                                     ServerWebExchange exchange) {
        @SuppressWarnings("unchecked")
        List<String> idStrings = (List<String>) body.get("ids");
        String opName = (String) body.get("op");
        if (idStrings == null || idStrings.isEmpty()) {
            throw new BizException("ops.batch.empty_ids");
        }
        if (idStrings.size() > 100) {
            throw new BizException("ops.batch.too_many", idStrings.size());
        }
        if (opName == null || opName.isBlank()) {
            throw new BizException("ops.batch.missing_op");
        }
        BatchOp op;
        try {
            op = BatchOp.valueOf(opName.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            throw new BizException("ops.batch.invalid_op");
        }

        List<UUID> ids = idStrings.stream().map(UUID::fromString).collect(Collectors.toList());
        var locale = Locales.uiLocale(exchange.getRequest().getHeaders());

        List<BatchResult.BatchResultItem> items = new ArrayList<>();
        int accepted = 0;
        for (UUID id : ids) {
            try {
                switch (op) {
                    case RERUN -> opsService.rerunInstance(id);
                    case KILL -> opsService.killTask(id);
                    case SET_SUCCESS -> opsService.setSuccess(id);
                }
                accepted++;
                items.add(new BatchResult.BatchResultItem(
                        id.toString(), "EXECUTED", null));
            } catch (BizException e) {
                items.add(new BatchResult.BatchResultItem(
                        id.toString(), "REJECTED", null));
            } catch (Exception e) {
                items.add(new BatchResult.BatchResultItem(
                        id.toString(), "REJECTED", null));
            }
        }

        BatchResult batchResult = new BatchResult(ids.size(), accepted, items);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", accepted == ids.size() ? "EXECUTED"
                : accepted > 0 ? "PARTIAL" : "REJECTED");
        result.put("result", batchResult);
        return ApiResponse.ok(result);
    }

    /**
     * 提交补数据 — 契约①。
     */
    @PostMapping("/backfill")
    public ApiResponse<Map<String, Object>> backfill(@RequestBody BackfillRequest req,
                                                      ServerWebExchange exchange) {
        var locale = Locales.uiLocale(exchange.getRequest().getHeaders());

        ActionRequest actionReq = ActionRequest.builder()
                .toolName("backfill").actionType("BACKFILL")
                .targetType(req.targetType().toUpperCase())
                .targetId(String.valueOf(req.targetId()))
                .actor("ui").actorSource("UI")
                .summary(opsMessages.get("ops.approval.backfill", locale))
                .param("dateStart", req.dateStart())
                .param("dateEnd", req.dateEnd())
                .param("includeDownstream", req.includeDownstream())
                .param("parallelism", req.parallelism())
                // 影响面：目标自身 + 勾选下游数,喂入 PolicyEngine 数据驱动分级(大批量补数据可升级审批)。
                .param("affectedTargetCount", 1 + req.downstreamTaskIds().size())
                .build();

        GateResult gr = gatedActionService.submit(actionReq, locale);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", gr.outcome().name());
        if (gr.executed()) {
            try {
                BackfillRun run = dataOpsBridge.submitBackfill(req);
                result.put("run", run);
            } catch (UnsupportedOperationException e) {
                result.put("message", "闸门通过，领域执行待 Stream A 实现");
                result.put("run", null);
            }
        } else {
            result.put("message", gr.message());
            result.put("approvalId", gr.actionId());
        }
        return ApiResponse.ok(result);
    }

    /**
     * 补数据下游影响范围预览 — 沿血缘解析目标任务的下游任务(id/名称/类目节点/层级),供前端可勾选展示。
     * M1 仅 task 目标支持下游子集(workflow 维持整 DAG,见 design 开放问题③);只读,不经闸门。
     */
    @GetMapping("/backfill/downstream-preview")
    public ApiResponse<List<OpsContracts.DownstreamTaskView>> backfillDownstreamPreview(
            @RequestParam(defaultValue = "task") String targetType,
            @RequestParam Long targetId) {
        return ApiResponse.ok(dataOpsBridge.previewDownstream(targetType, targetId));
    }

    /**
     * 查询补数据运行列表 — 契约①。多维筛选 + 分页，返回 {@link Page} 信封。
     * state CSV 多选（含 PARTIAL 部分失败）/ targetName 模糊 / targetType / bizDate 区间 / createdBy。
     */
    @GetMapping("/backfill")
    public ApiResponse<?> backfillList(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String targetName,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String bizDateFrom,
            @RequestParam(required = false) String bizDateTo,
            @RequestParam(required = false) Long createdBy,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Long pid = resolveProjectId(projectId);
            Page<BackfillRun> result = dataOpsBridge.queryBackfillRuns(
                    state, targetName, targetType, bizDateFrom, bizDateTo, createdBy,
                    pid, page, size);
            return ApiResponse.ok(result);
        } catch (UnsupportedOperationException e) {
            return ApiResponse.ok(new Page<>(List.of(), 0, page, size));
        }
    }

    /**
     * 查询单个补数据运行详情 — 契约①。
     */
    @GetMapping("/backfill/{runId}")
    public ApiResponse<Map<String, Object>> backfillDetail(@PathVariable UUID runId) {
        try {
            BackfillRun run = dataOpsBridge.backfillRun(runId);
            List<InstanceRow> instances = dataOpsBridge.backfillRunInstances(runId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("run", run);
            result.put("instances", instances);
            return ApiResponse.ok(result);
        } catch (UnsupportedOperationException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("run", null);
            result.put("instances", List.of());
            result.put("note", "待 Stream A 实现");
            return ApiResponse.ok(result);
        }
    }

    /**
     * 节点级 DAG 冻结/解冻（ops-center-publish-boundary，取代退役的任务级 /tasks/{id}/freeze）。
     * instanceId 省略=定义级（后续每个 cron 实例跳该节点及其传递下游）；非空=实例级（仅该实例）。
     * 冻结状态存运维侧 overlay，不写入发布快照。写操作经闸门留痕。
     */
    @PostMapping("/workflows/{workflowId}/nodes/{nodeKey}/freeze")
    public ApiResponse<Map<String, Object>> freezeNode(@PathVariable Long workflowId,
                                                       @PathVariable String nodeKey,
                                                       @RequestBody Map<String, Object> body,
                                                       ServerWebExchange exchange) {
        boolean frozen = Boolean.TRUE.equals(body.get("frozen"));
        UUID instanceId = body.get("instanceId") != null
                ? UUID.fromString(String.valueOf(body.get("instanceId"))) : null;
        var locale = Locales.uiLocale(exchange.getRequest().getHeaders());

        ActionRequest actionReq = ActionRequest.builder()
                .toolName("freeze_node").actionType("FREEZE_NODE")
                .targetType("WORKFLOW_NODE").targetId(workflowId + "/" + nodeKey)
                .actor("ui").actorSource("UI")
                .summary(opsMessages.get("ops.approval.freeze_node", locale) + " " + workflowId + "/" + nodeKey)
                .param("frozen", frozen)
                .param("instanceId", instanceId == null ? null : instanceId.toString())
                .build();

        GateResult gr = gatedActionService.submit(actionReq, locale);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", gr.outcome().name());

        if (gr.executed()) {
            try {
                dataOpsBridge.setNodeFrozen(workflowId, nodeKey, instanceId, frozen);
                result.put("node", Map.of("workflowId", workflowId, "nodeKey", nodeKey,
                        "scope", instanceId == null ? "DEFINITION" : "INSTANCE", "frozen", frozen));
            } catch (UnsupportedOperationException e) {
                result.put("message", "闸门通过，领域执行待 Stream A 实现");
                result.put("node", null);
            }
        } else {
            result.put("message", gr.message());
            result.put("approvalId", gr.actionId());
        }
        return ApiResponse.ok(result);
    }

    /**
     * 获取 DAG 节点关联任务的发布版本详情（003-node-detail-panel）。
     * 从已发布 DAG 快照中定位节点，按 taskId + taskVersionNo 查询
     * {@code task_def_version} 返回发布时冻结的任务配置（代码/参数等）。
     * VIRTUAL 节点返回 400；快照或节点不存在返回 404。
     */
    @GetMapping("/workflows/{workflowId}/nodes/{nodeKey}/detail")
    public ApiResponse<NodeTaskDetail> nodeDetail(@PathVariable Long workflowId,
                                                   @PathVariable String nodeKey) {
        return ApiResponse.ok(workflowService.getNodeDetail(workflowId, nodeKey));
    }

    // ─── 现有日志 / 指标 / SSE ──────────────────────

    @GetMapping("/instances/{id}/log")
    public ApiResponse<?> log(@PathVariable UUID id,
                                 @RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "65536") int limit) {
        return ApiResponse.ok(opsService.getLog(id, offset, limit));
    }

    // ─── 系统指标（Phase 5） ───────────────────────────────────

    /** 调度四层指标聚合快照（供前端指标看板）。 */
    @GetMapping("/metrics")
    public ApiResponse<MetricsSnapshot> metrics() {
        // 先刷新 DB 衍生指标
        metrics.refreshQueueDepth();
        metrics.refreshOldestAge();
        metrics.refreshSlotUtilization();
        metrics.refreshFragmentation();
        metrics.setLogStreamBacklog(logBus.totalBacklog());
        return ApiResponse.ok(metrics.snapshot());
    }

    // ─── 实时 SSE 端点 ─────────────────────────────────────────

    /**
     * 任务实例日志实时流：从 LogBus 读取实时日志，支持 Last-Event-ID 断线续传。
     * 若实例已结束（state=SUCCESS/FAILED），从归档读取历史日志。
     */
    @GetMapping(value = "/instances/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> logStream(@PathVariable UUID id,
                                                     @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        // 检查实例是否已结束（含 TEST 试跑——不能用 opsService.instances()，其排除 TEST），若结束则读历史日志
        TaskInstance inst = opsService.findInstance(id).orElse(null);

        if (inst != null && ("SUCCESS".equals(inst.getState()) || "FAILED".equals(inst.getState())
                || "STOPPED".equals(inst.getState()) || "KILLED".equals(inst.getState()))) {
            return streamEndedLogs(id, inst);
        }

        // 实时流：每 200ms 轮询 LogBus 推日志；每 ~2s 顺带查实例状态，终态则 emit 带 outcome 的 end 并关流。
        // 终态检出当 tick 会先读日志再查状态，故无尾日志丢失；end 事件由外层 takeUntil 触发流完成，避免悬挂。
        final String[] afterId = {lastEventId};
        final long stateCheckEvery = 10; // 10 × 200ms = 2s
        return Flux.interval(Duration.ofMillis(200))
                .doOnSubscribe(s -> sseConnect())
                .doFinally(s -> sseDisconnect())
                .concatMap(tick -> {
                    List<ServerSentEvent<String>> out = new ArrayList<>();
                    for (LogBus.Entry entry : logBus.read(id, afterId[0], 100)) {
                        afterId[0] = entry.id();
                        out.add(ServerSentEvent.<String>builder()
                                .id(entry.id())
                                .event("log")
                                .data(entry.line())
                                .build());
                    }
                    if (tick % stateCheckEvery == 0) {
                        String state = opsService.findInstance(id).map(TaskInstance::getState).orElse(null);
                        if (state != null && InstanceStates.isTerminal(state)) {
                            out.add(endSse(state));
                        }
                    }
                    return Flux.fromIterable(out);
                })
                // emit end 后立即完成，关闭 SSE，杜绝 live 路径任务结束后流悬挂
                .takeUntil(sse -> "end".equals(sse.event()));
    }

    /**
     * 已结束实例的历史日志：按优先级回退取全量——
     * ① 内存/Redis 日志总线（all-in-one 下 InMemoryLogBus 仍保有全量行，且 distributed 下 Redis Stream 亦在）；
     * ② 归档存储（logs/{biz_date}/{instance_id}/{attempt}.log，MinIO/文件）；
     * ③ 持久化的 {@code task_instance.log}（尾部摘要兜底）。
     * 任一命中即逐行回放 + {@code end}；全空则仅 {@code end}（前端显示「无日志记录」）。
     *
     * <p>修复：原实现只读归档，但 all-in-one 模式归档从不写入 → 试跑/手动运行（秒级结束）的日志恒为空。
     */
    private Flux<ServerSentEvent<String>> streamEndedLogs(UUID id, TaskInstance inst) {
        // ① 日志总线（保有全量逐行）
        List<LogBus.Entry> busEntries = logBus.read(id, null, 5000);
        if (!busEntries.isEmpty()) {
            return Flux.fromIterable(busEntries)
                    .map(e -> ServerSentEvent.<String>builder()
                            .id(e.id()).event("log").data(e.line()).build())
                    .concatWith(endEvent(inst.getState()));
        }

        // ② 归档存储
        String bizDate = inst.getBizDate() != null ? inst.getBizDate().toString() : "unknown";
        String key = String.format("logs/%s/%s/%d.log", bizDate, id, inst.getAttempt());
        var content = logArchive.get(key);
        String full = content.orElse(null);

        // ③ 持久化尾部兜底
        if ((full == null || full.isEmpty()) && inst.getLog() != null && !inst.getLog().isEmpty()) {
            full = inst.getLog();
        }
        if (full == null || full.isEmpty()) {
            return endEvent(inst.getState());
        }

        String[] lines = full.split("\n");
        return Flux.fromArray(lines)
                .index()
                .map(tuple -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(tuple.getT1()))
                        .event("log")
                        .data(tuple.getT2())
                        .build())
                .concatWith(endEvent(inst.getState()));
    }

    /** 终态关闭事件：data 丰化为 JSON state 对象（event 名保持 "end"，对只按事件名判定结束的客户端非破坏）。 */
    private ServerSentEvent<String> endSse(String state) {
        return ServerSentEvent.<String>builder()
                .event("end")
                .data("{\"state\":\"" + state + "\"}")
                .build();
    }

    private Flux<ServerSentEvent<String>> endEvent(String state) {
        return Flux.just(endSse(state));
    }

    /**
     * 工作流实例状态事件流：订阅 EventBus，实时推送 DAG 节点状态变迁。
     */
    @GetMapping(value = "/workflow-instances/{id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> workflowEventsStream(@PathVariable UUID id) {
        String channel = "dw:evt:" + id;
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();

        var subscription = eventBus.subscribe(channel, message -> {
            sink.tryEmitNext(ServerSentEvent.<String>builder()
                    .event("status")
                    .data(message)
                    .build());
        });

        return sink.asFlux()
                .doOnSubscribe(s -> sseConnect())
                .doFinally(s -> {
                    sseDisconnect();
                    try {
                        subscription.close();
                    } catch (Exception e) {
                        // 忽略
                    }
                });
    }

    // ─── helpers ────────────────────────────────────────

    /** SSE 长连接 +1（订阅时调一次，同步推 Gauge）。 */
    private void sseConnect() {
        metrics.setSseConnections(sseConnCount.incrementAndGet());
    }

    /** SSE 长连接 -1（任意终止时调一次，同步推 Gauge）。与 sseConnect 经 doOnSubscribe/doFinally 严格配对。 */
    private void sseDisconnect() {
        metrics.setSseConnections(sseConnCount.decrementAndGet());
    }

    /** 构造批量操作请求（每个 instance 独立一个 ActionRequest，逐经闸门）。 */
    private ActionRequest buildBatchActionRequest(UUID instanceId, BatchOp op) {
        // 运维中心批量针对单个 task_instance —— 用独立 OPS_* 动作类型，避免与既有
        // TASK_RERUN（按 taskId 新建）/KILL_INSTANCE（杀工作流实例）语义混淆（data-ops-center 集成对齐）。
        String actionType = switch (op) {
            case RERUN -> "OPS_RERUN_INSTANCE";
            case KILL -> "OPS_KILL_INSTANCE";
            case SET_SUCCESS -> "OPS_SET_SUCCESS";
        };
        String summary = switch (op) {
            case RERUN -> "重跑实例 #" + instanceId;
            case KILL -> "终止实例 #" + instanceId;
            case SET_SUCCESS -> "置成功实例 #" + instanceId;
        };
        return ActionRequest.builder()
                .toolName("batch_" + op.name().toLowerCase())
                .actionType(actionType)
                .targetType("TASK_INSTANCE")
                .targetId(String.valueOf(instanceId))
                .actor("ui")
                .actorSource("UI")
                .summary(summary)
                .param("instanceId", instanceId.toString())
                .build();
    }
}
