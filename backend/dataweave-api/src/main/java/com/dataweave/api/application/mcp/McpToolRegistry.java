package com.dataweave.api.application.mcp;

import com.dataweave.api.application.DataOpsBridge;
import com.dataweave.api.infrastructure.OpsMessages;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.api.interfaces.dto.BackfillRequest;
import com.dataweave.api.interfaces.dto.BackfillRun;
import com.dataweave.api.interfaces.dto.BatchOp;
import com.dataweave.api.interfaces.dto.InstanceQuery;
import com.dataweave.api.interfaces.dto.InstanceRow;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.ApprovalService;
import com.dataweave.master.application.FleetService;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.LineageService;
import com.dataweave.master.application.MetricService;
import com.dataweave.master.application.OpsService;
import com.dataweave.master.application.ProjectSyncDtos;
import com.dataweave.master.application.ProjectSyncService;
import com.dataweave.master.application.TaskService;
import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.AtomicMetric;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 平台工具注册中心：平台能力的唯一真相源。查询工具直通 master 领域服务（与观测页 REST 同源）；
 * 写工具（project_push/task_rerun/node_exec）全部经 {@link GatedActionService} 闸门，无绕过路径。
 *
 * <p>输出超阈值时截断并附标记，完整输出存档到本地文件（M2 迁 MinIO），返回引用。
 *
 * <p>handler 接收 {@link McpTool.Context}（参数 + agent locale），写工具经闸门将 locale 透传给
 * {@link GatedActionService#submit(ActionRequest, Locale)} 本地化裁决理由与闸门响应。
 */
@Component
public class McpToolRegistry {

    private final TaskDefRepository taskDefRepository;
    private final TaskInstanceRepository instanceRepository;
    private final FleetService fleetService;
    private final MetricService metricService;
    private final LineageService lineageService;
    private final GatedActionService gatedActionService;
    private final ApprovalService approvalService;
    private final TaskService taskService;
    private final OpsService opsService;
    private final ProjectSyncService projectSyncService;
    private final DataOpsBridge dataOpsBridge;
    private final OpsMessages opsMessages;
    private final ObjectMapper objectMapper;
    private final int outputThreshold;
    private final Path archiveDir;

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public McpToolRegistry(TaskDefRepository taskDefRepository,
                           TaskInstanceRepository instanceRepository,
                           FleetService fleetService,
                           MetricService metricService,
                           LineageService lineageService,
                           GatedActionService gatedActionService,
                           ApprovalService approvalService,
                           TaskService taskService,
                           OpsService opsService,
                           ProjectSyncService projectSyncService,
                           DataOpsBridge dataOpsBridge,
                           OpsMessages opsMessages,
                           ObjectMapper objectMapper,
                           @Value("${mcp.output-threshold:8000}") int outputThreshold,
                           @Value("${mcp.archive-dir:${java.io.tmpdir}/dataweave-tool-outputs}") String archiveDir) {
        this.taskDefRepository = taskDefRepository;
        this.instanceRepository = instanceRepository;
        this.fleetService = fleetService;
        this.metricService = metricService;
        this.lineageService = lineageService;
        this.gatedActionService = gatedActionService;
        this.approvalService = approvalService;
        this.taskService = taskService;
        this.opsService = opsService;
        this.projectSyncService = projectSyncService;
        this.dataOpsBridge = dataOpsBridge;
        this.opsMessages = opsMessages;
        this.objectMapper = objectMapper;
        this.outputThreshold = outputThreshold;
        this.archiveDir = Path.of(archiveDir);
        registerTools();
    }

    public List<McpTool> list() {
        return new ArrayList<>(tools.values());
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** 默认 locale（中文）执行。 */
    public ToolResult call(String name, Map<String, Object> args) {
        return call(name, args, Locale.SIMPLIFIED_CHINESE, null, null);
    }

    /** 按 agent locale 执行：闸门响应本地化走此 locale。 */
    public ToolResult call(String name, Map<String, Object> args, Locale locale) {
        return call(name, args, locale, null, null);
    }

    /** 按 agent locale + 租户身份执行（E1 MCP 身份注入）。 */
    public ToolResult call(String name, Map<String, Object> args, Locale locale, Long tenantId, Long userId) {
        McpTool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.error("未知工具：" + name);
        }
        try {
            McpTool.Context ctx = new McpTool.Context(args == null ? Map.of() : args, locale, tenantId, userId);
            Object payload = tool.handler().apply(ctx);
            String text = payload instanceof String s ? s : objectMapper.writeValueAsString(payload);
            return truncateAndArchive(name, text);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误：" + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("工具执行失败：" + e.getMessage());
        }
    }

    // ============================ 工具注册 ============================

    private void registerTools() {
        // ---- 项目同步工具（E 新增，复用 C ProjectSyncService；pull/diff 只读，push 经闸门）----
        register("project_pull", "拉取项目全部文件化定义（只读，租户隔离）",
                schema(req("projectId", "integer", "项目 id")), ctx -> {
                    Long tenantId = requireTenant(ctx);
                    Long projectId = requiredLong(ctx.args(), "projectId");
                    ProjectSyncDtos.PullResult r = projectSyncService.pull(projectId, tenantId);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("projectId", r.projectId());
                    out.put("baseline", r.baseline());
                    out.put("fileCount", r.fileCount());
                    out.put("files", r.bundle().files());
                    return out;
                });

        register("project_diff", "对比本地文件与服务器定义差异（只读，租户隔离，零写入）",
                schema(req("projectId", "integer", "项目 id"),
                        req("files", "object", "文件集 { path → content }"),
                        prop("baseline", "string", "基线令牌，可选")), ctx -> {
                    Long tenantId = requireTenant(ctx);
                    Long projectId = requiredLong(ctx.args(), "projectId");
                    @SuppressWarnings("unchecked")
                    Map<String, String> files = (Map<String, String>) ctx.args().get("files");
                    if (files == null || files.isEmpty()) {
                        throw new BizException("mcp.param_required", "files");
                    }
                    String baseline = str(ctx.args(), "baseline");
                    ProjectSyncDtos.PushCommand cmd = new ProjectSyncDtos.PushCommand(
                            files, baseline, false, null, null);
                    ProjectSyncDtos.DiffPreview diff = projectSyncService.diff(projectId, tenantId, cmd);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("added", diff.added());
                    out.put("modified", diff.modified());
                    out.put("removed", diff.removed());
                    out.put("stale", diff.stale());
                    return out;
                });

        register("project_push", "将本地文件定义推送回服务器（经策略闸门，租户隔离；纯增改 L1 直通，含删除/force L2 审批）",
                schema(req("projectId", "integer", "项目 id"),
                        req("files", "object", "文件集 { path → content }"),
                        prop("baseline", "string", "基线令牌，可选"),
                        prop("force", "boolean", "force 覆盖（跳过基线校验），默认 false"),
                        prop("remark", "string", "提交备注，可选")), ctx -> {
                    Long tenantId = requireTenant(ctx);
                    Long userId = ctx.userId() != null ? ctx.userId() : TenantContext.userId();
                    Long projectId = requiredLong(ctx.args(), "projectId");
                    @SuppressWarnings("unchecked")
                    Map<String, String> files = (Map<String, String>) ctx.args().get("files");
                    if (files == null || files.isEmpty()) {
                        throw new BizException("mcp.param_required", "files");
                    }
                    String baseline = str(ctx.args(), "baseline");
                    boolean force = Boolean.TRUE.equals(ctx.args().get("force"));
                    String remark = str(ctx.args(), "remark");

                    // E3: 风险自适应 —— 先 diff 算 removed
                    ProjectSyncDtos.PushCommand previewCmd = new ProjectSyncDtos.PushCommand(
                            files, baseline, force, null, remark);
                    ProjectSyncDtos.DiffPreview diff = projectSyncService.diff(projectId, tenantId, previewCmd);
                    boolean destructive = !diff.removed().isEmpty() || force;

                    // 选择 actionType（匹配 policy_rules seed：PROJECT_PUSH=L1 / PROJECT_PUSH_DESTRUCTIVE=L2）
                    String actionType = destructive ? "PROJECT_PUSH_DESTRUCTIVE" : "PROJECT_PUSH";
                    int created = diff.added().size();
                    int modified = diff.modified().size();
                    int removed = diff.removed().size();

                    // 编码 payload 经 command → 执行器解码 → ProjectSyncService.push
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("projectId", projectId);
                    payload.put("tenantId", tenantId);
                    payload.put("userId", userId);
                    payload.put("files", files);
                    payload.put("baseline", baseline);
                    payload.put("force", force);
                    payload.put("remark", remark);
                    String payloadJson;
                    try {
                        payloadJson = objectMapper.writeValueAsString(payload);
                    } catch (Exception e) {
                        throw new BizException("project.sync.invalid", e.getMessage());
                    }

                    ActionRequest req = ActionRequest.builder()
                            .toolName(actionType).actionType(actionType)
                            .targetType("PROJECT").targetId(String.valueOf(projectId))
                            .command(payloadJson)
                            .actor("agent").actorSource("AGENT")
                            .summary("推送项目 #" + projectId + " 定义（新增" + created
                                    + "/更新" + modified + "/删除" + removed + "）"
                                    + (destructive ? " [破坏性，需审批]" : ""))
                            .build();
                    return gateText(gatedActionService.submit(req, ctx.locale()));
                });

        // ---- 查询工具（L0，直通领域服务）----
        register("query_task_definitions", "列出本租户全部任务定义（与任务管理页 REST 同源，租户隔离）",
                schema(), ctx -> {
                    Long tenantId = requireTenant(ctx);
                    return taskDefRepository.findByTenantIdAndDeleted(tenantId, 0);
                });

        register("query_task_instances", "列出本租户任务实例，可选按 state 过滤（FAILED/SUCCESS/RUNNING…，租户隔离）",
                schema(prop("state", "string", "运行状态，可选")), ctx -> {
                    Long tenantId = requireTenant(ctx);
                    String state = str(ctx.args(), "state");
                    if (state != null && !state.isBlank()) {
                        return instanceRepository.findByTenantIdAndState(tenantId, state);
                    }
                    return instanceRepository.findByTenantId(tenantId);
                });

        register("query_fleet", "列出本租户 worker 机器集群与资源水位（租户隔离）",
                schema(), ctx -> {
                    requireTenant(ctx);
                    return fleetService.nodes();
                });

        register("query_metric", "按指标 code 返回当前值与口径溯源（租户隔离）",
                schema(req("code", "string", "指标 code，如 GMV")), ctx -> {
                    requireTenant(ctx);
                    String code = required(ctx.args(), "code");
                    Optional<AtomicMetric> m = metricService.findLatestByCode(code);
                    if (m.isEmpty()) {
                        return Map.of("found", false, "code", code);
                    }
                    AtomicMetric metric = m.get();
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("found", true);
                    r.put("name", metric.getName());
                    r.put("value", metricService.evaluate(metric));
                    r.put("exprSql", metric.getMeasureExpr());
                    r.put("sourceTable", metric.getSourceTable());
                    r.put("version", metric.getVersionNo());
                    return r;
                });

        register("query_lineage", "按指标 code 返回血缘链路（指标 → SQL → 物理表，租户隔离）",
                schema(req("code", "string", "指标 code，如 GMV")), ctx -> {
                    requireTenant(ctx);
                    String code = required(ctx.args(), "code");
                    return lineageService.lineageOf(code)
                            .map(p -> (Object) Map.of(
                                    "metric", p.metric().getName(),
                                    "exprSql", p.metric().getMeasureExpr(),
                                    "lineage", p.lineage()))
                            .orElse(Map.of("found", false, "code", code));
                });

        // ---- 只读日志工具（E 新增，租户隔离）----
        register("instance_logs", "读取本租户任务实例的运行日志快照（租户隔离）",
                schema(req("instanceId", "string", "任务实例 id（UUID）"),
                        prop("offset", "integer", "字节偏移，默认 0"),
                        prop("limit", "integer", "返回字节数上限，默认 8000")),
                ctx -> {
                    Long tenantId = requireTenant(ctx);
                    java.util.UUID instanceId = requiredUuid(ctx.args(), "instanceId");
                    // 租户校验：实例必须属本租户
                    com.dataweave.master.domain.TaskInstance inst =
                            instanceRepository.findById(instanceId).orElse(null);
                    if (inst == null || !tenantId.equals(inst.getTenantId())) {
                        throw new BizException("mcp.tenant_required");
                    }
                    int offset = lng(ctx.args(), "offset") != null
                            ? lng(ctx.args(), "offset").intValue() : 0;
                    int limit = lng(ctx.args(), "limit") != null
                            ? lng(ctx.args(), "limit").intValue() : 8000;
                    var chunk = opsService.getLog(instanceId, offset, limit);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("content", chunk.content());
                    r.put("totalSize", chunk.totalSize());
                    r.put("offset", chunk.offset());
                    r.put("hasMore", chunk.hasMore());
                    return r;
                });

        // ---- 写工具（全部经 PolicyEngine 闸门；create_task 已移除，定义写入一律走 project_push）----

        register("task_rerun", "重跑一个本租户任务实例（经策略闸门，租户隔离）",
                schema(req("instanceId", "string", "任务实例 id（UUID）")),
                ctx -> {
                    Long tenantId = requireTenant(ctx);
                    java.util.UUID instanceId = requiredUuid(ctx.args(), "instanceId");
                    // 租户校验：实例必须属本租户
                    TaskInstance inst = instanceRepository.findById(instanceId).orElse(null);
                    if (inst == null || !tenantId.equals(inst.getTenantId())) {
                        throw new BizException("mcp.tenant_required");
                    }
                    ActionRequest req = ActionRequest.builder()
                            .toolName("task_rerun").actionType("TASK_RERUN")
                            .targetType("TASK_INSTANCE").targetId(String.valueOf(instanceId))
                            .actor("agent").actorSource("AGENT")
                            .summary("重跑实例 #" + instanceId)
                            .build();
                    return gateText(gatedActionService.submit(req, ctx.locale()));
                });

        register("node_exec", "在指定节点执行受控命令（白名单前缀 + 命令串安全解析，经策略闸门，租户隔离）",
                schema(req("nodeCode", "string", "目标节点 code"),
                        req("command", "string", "命令串（白名单前缀）")),
                ctx -> {
                    requireTenant(ctx);
                    String nodeCode = required(ctx.args(), "nodeCode");
                    String command = required(ctx.args(), "command");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("node_exec").actionType("NODE_EXEC")
                            .targetType("NODE").targetId(nodeCode)
                            .command(command)
                            .actor("agent").actorSource("AGENT")
                            .summary("在 " + nodeCode + " 执行：" + command)
                            .build();
                    return gateText(gatedActionService.submit(req, ctx.locale()));
                });

        // ---- 审批续做：观察审批单状态/结果（不自行批准，人审在审批卡片）----
        register("approve_and_execute", "查看审批单状态与执行结果（人工批准在右舷审批卡片完成；此工具用于续做时确认结果，租户隔离）",
                schema(req("approvalId", "integer", "审批单 id")),
                ctx -> {
                    requireTenant(ctx);
                    Long id = requiredLong(ctx.args(), "approvalId");
                    Optional<AgentAction> a = approvalService.get(id);
                    if (a.isEmpty()) {
                        return Map.of("found", false, "approvalId", id);
                    }
                    AgentAction action = a.get();
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("approvalId", id);
                    r.put("status", action.getApprovalStatus());
                    r.put("level", action.getPolicyLevel());
                    r.put("summary", action.getSummary());
                    r.put("result", action.getResultJson());
                    if ("PENDING".equals(action.getApprovalStatus())) {
                        r.put("note", "仍待人工批准（右舷审批卡片）");
                    }
                    return r;
                });

        // ---- CRUD 工具已移除（E 子特性，定义写入一律走 project_push）----

        register("pause_instance", "暂停工作流实例（经策略闸门，租户隔离）",
                schema(req("instanceId", "string", "工作流实例 id（UUID）")),
                ctx -> {
                    requireTenant(ctx);
                    java.util.UUID instanceId = requiredUuid(ctx.args(), "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("pause_instance").actionType("PAUSE_INSTANCE")
                            .targetType("WORKFLOW_INSTANCE").targetId(String.valueOf(instanceId))
                            .actor("agent").actorSource("AGENT")
                            .summary("暂停实例 #" + instanceId)
                            .build();
                    GateResult gr = gatedActionService.submit(req, ctx.locale());
                    if (gr.pending() || "DENIED".equals(gr.outcome().name())) return gateText(gr);
                    return opsService.pauseWorkflow(instanceId);
                });

        register("resume_instance", "恢复工作流实例（经策略闸门，租户隔离）",
                schema(req("instanceId", "string", "工作流实例 id（UUID）")),
                ctx -> {
                    requireTenant(ctx);
                    java.util.UUID instanceId = requiredUuid(ctx.args(), "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("resume_instance").actionType("RESUME_INSTANCE")
                            .targetType("WORKFLOW_INSTANCE").targetId(String.valueOf(instanceId))
                            .actor("agent").actorSource("AGENT")
                            .summary("恢复实例 #" + instanceId)
                            .build();
                    GateResult gr = gatedActionService.submit(req, ctx.locale());
                    if (gr.pending() || "DENIED".equals(gr.outcome().name())) return gateText(gr);
                    return opsService.resumeWorkflow(instanceId);
                });

        register("kill_instance", "终止工作流实例（经策略闸门，租户隔离）",
                schema(req("instanceId", "string", "工作流实例 id（UUID）")),
                ctx -> {
                    requireTenant(ctx);
                    java.util.UUID instanceId = requiredUuid(ctx.args(), "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("kill_instance").actionType("KILL_INSTANCE")
                            .targetType("WORKFLOW_INSTANCE").targetId(String.valueOf(instanceId))
                            .actor("agent").actorSource("AGENT")
                            .summary("终止实例 #" + instanceId)
                            .build();
                    GateResult gr = gatedActionService.submit(req, ctx.locale());
                    if (gr.pending() || "DENIED".equals(gr.outcome().name())) return gateText(gr);
                    return opsService.killWorkflow(instanceId);
                });

        // ---- 调度类写工具（distributed-scheduler-m1，全部经闸门）----
        register("test_run", "单任务测试运行：下发草稿内容到 worker，不入正式统计（经策略闸门，留痕，租户隔离）",
                schema(req("taskId", "integer", "任务定义 id"),
                        prop("bizDate", "string", "业务日期 yyyy-MM-dd，可选")),
                ctx -> {
                    requireTenant(ctx);
                    Long taskId = requiredLong(ctx.args(), "taskId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("test_run").actionType("TEST_RUN")
                            .targetType("TASK").targetId(String.valueOf(taskId))
                            .command(str(ctx.args(), "bizDate"))
                            .actor("agent").actorSource("AGENT")
                            .summary("测试运行任务 #" + taskId)
                            .build();
                    return gateText(gatedActionService.submit(req, ctx.locale()));
                });

        register("trigger_workflow", "手动触发工作流执行（经策略闸门，租户隔离）",
                schema(req("workflowId", "integer", "工作流定义 id"),
                        prop("bizDate", "string", "业务日期 yyyy-MM-dd，可选")),
                ctx -> {
                    requireTenant(ctx);
                    Long workflowId = requiredLong(ctx.args(), "workflowId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("trigger_workflow").actionType("TRIGGER_WORKFLOW")
                            .targetType("WORKFLOW").targetId(String.valueOf(workflowId))
                            .command(str(ctx.args(), "bizDate"))
                            .actor("agent").actorSource("AGENT")
                            .summary("手动触发工作流 #" + workflowId)
                            .build();
                    return gateText(gatedActionService.submit(req, ctx.locale()));
                });

        register("resume_workflow", "断点恢复失败的工作流实例：保留成功节点，从失败点续跑（经策略闸门，租户隔离）",
                schema(req("instanceId", "string", "工作流实例 id（UUID）")),
                ctx -> {
                    requireTenant(ctx);
                    java.util.UUID instanceId = requiredUuid(ctx.args(), "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("resume_workflow").actionType("RESUME_WORKFLOW")
                            .targetType("WORKFLOW_INSTANCE").targetId(instanceId.toString())
                            .actor("agent").actorSource("AGENT")
                            .summary("断点恢复实例 " + instanceId)
                            .build();
                    return gateText(gatedActionService.submit(req, ctx.locale()));
                });

        register("rerun_workflow", "整流重跑工作流实例：全节点重置后重跑（经策略闸门，租户隔离）",
                schema(req("instanceId", "string", "工作流实例 id（UUID）")),
                ctx -> {
                    requireTenant(ctx);
                    java.util.UUID instanceId = requiredUuid(ctx.args(), "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("rerun_workflow").actionType("RERUN_WORKFLOW")
                            .targetType("WORKFLOW_INSTANCE").targetId(instanceId.toString())
                            .actor("agent").actorSource("AGENT")
                            .summary("整流重跑实例 " + instanceId)
                            .build();
                    return gateText(gatedActionService.submit(req, ctx.locale()));
                });

        // ─── data-ops-center Stream C: 运维工具 ──────────────────

        register("query_failed_instances", "查询本租户最近失败的周期实例（可选按 taskId 过滤，租户隔离）",
                schema(prop("taskId", "integer", "任务定义 id，可选"),
                        prop("limit", "integer", "返回数量上限，默认 20")),
                ctx -> {
                    requireTenant(ctx);
                    Long taskId = lng(ctx.args(), "taskId");
                    Long limit = lng(ctx.args(), "limit");
                    int size = limit != null ? limit.intValue() : 20;
                    var q = new InstanceQuery(null, "FAILED", taskId, null, 1, size);
                    return dataOpsBridge.queryInstances(q);
                });

        register("rerun_instance", "重跑一个失败的任务实例（经策略闸门，租户隔离）",
                schema(req("instanceId", "string", "任务实例 id（UUID）")),
                ctx -> {
                    requireTenant(ctx);
                    java.util.UUID instanceId = requiredUuid(ctx.args(), "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("rerun_instance").actionType("TASK_RERUN")
                            .targetType("TASK_INSTANCE").targetId(instanceId.toString())
                            .actor("agent").actorSource("AGENT")
                            .summary(opsMessages.get("ops.approval.rerun", ctx.locale()) + " #" + instanceId)
                            .build();
                    return gateText(gatedActionService.submit(req, ctx.locale()));
                });

        register("set_instance_success", "将任务实例标记为成功（经策略闸门，租户隔离）",
                schema(req("instanceId", "string", "任务实例 id（UUID）")),
                ctx -> {
                    requireTenant(ctx);
                    java.util.UUID instanceId = requiredUuid(ctx.args(), "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("set_instance_success").actionType("SET_SUCCESS")
                            .targetType("TASK_INSTANCE").targetId(instanceId.toString())
                            .actor("agent").actorSource("AGENT")
                            .summary(opsMessages.get("ops.approval.set_success", ctx.locale()) + " #" + instanceId)
                            .build();
                    GateResult gr = gatedActionService.submit(req, ctx.locale());
                    if (gr.pending() || "DENIED".equals(gr.outcome().name())) return gateText(gr);
                    try {
                        var inst = dataOpsBridge.setSuccess(instanceId);
                        return Map.of("outcome", "EXECUTED", "instanceId", instanceId.toString(),
                                "state", inst.getState());
                    } catch (UnsupportedOperationException e) {
                        return Map.of("outcome", "EXECUTED", "instanceId", instanceId.toString(),
                                "note", "领域执行待 Stream A 实现");
                    }
                });

        register("batch_instance_ops", "批量操作实例：rerun/kill/set-success（逐实例经策略闸门，租户隔离）",
                schema(req("instanceIds", "array", "实例 id 列表（UUID 字符串）"),
                        req("op", "string", "操作类型：rerun | kill | set-success")),
                ctx -> {
                    requireTenant(ctx);
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) ctx.args().get("instanceIds");
                    if (ids == null || ids.isEmpty()) {
                        throw new BizException("mcp.instance_ids_required");
                    }
                    String opName = required(ctx.args(), "op");
                    BatchOp op;
                    try {
                        op = BatchOp.valueOf(opName.toUpperCase().replace("-", "_"));
                    } catch (IllegalArgumentException e) {
                        throw new BizException("mcp.invalid_op");
                    }
                    // 逐实例经闸门，与 OpsController.batchOp 一致
                    List<UUID> uuids = ids.stream().map(UUID::fromString).toList();
                    String actionType = switch (op) {
                        case RERUN -> "TASK_RERUN";
                        case KILL -> "KILL_INSTANCE";
                        case SET_SUCCESS -> "SET_SUCCESS";
                    };
                    List<Map<String, Object>> results = new ArrayList<>();
                    int accepted = 0;
                    for (UUID id : uuids) {
                        ActionRequest req = ActionRequest.builder()
                                .toolName("batch_" + op.name().toLowerCase()).actionType(actionType)
                                .targetType("TASK_INSTANCE").targetId(id.toString())
                                .actor("agent").actorSource("AGENT")
                                .summary(opName + " #" + id)
                                .build();
                        GateResult gr = gatedActionService.submit(req, ctx.locale());
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", id.toString());
                        item.put("outcome", gr.outcome().name());
                        if (gr.pending()) item.put("approvalId", gr.actionId());
                        results.add(item);
                        if (gr.executed()) accepted++;
                    }
                    return Map.of("requested", uuids.size(), "accepted", accepted, "results", results);
                });

        register("submit_backfill", "提交补数据：对指定日期区间生成补数据实例（经策略闸门，租户隔离）",
                schema(req("targetType", "string", "task 或 workflow"),
                        req("targetId", "integer", "目标任务/工作流 id"),
                        req("dateStart", "string", "起始日期 yyyy-MM-dd"),
                        req("dateEnd", "string", "结束日期 yyyy-MM-dd"),
                        prop("includeDownstream", "boolean", "是否包含下游，默认 false"),
                        prop("parallelism", "integer", "并发度 1-10，默认 1")),
                ctx -> {
                    requireTenant(ctx);
                    BackfillRequest bfReq = new BackfillRequest(
                            required(ctx.args(), "targetType"),
                            requiredLong(ctx.args(), "targetId"),
                            required(ctx.args(), "dateStart"),
                            required(ctx.args(), "dateEnd"),
                            Boolean.TRUE.equals(ctx.args().get("includeDownstream")),
                            lng(ctx.args(), "parallelism") != null ? lng(ctx.args(), "parallelism").intValue() : 1);
                    ActionRequest req = ActionRequest.builder()
                            .toolName("submit_backfill").actionType("BACKFILL")
                            .targetType(bfReq.targetType().toUpperCase())
                            .targetId(String.valueOf(bfReq.targetId()))
                            .actor("agent").actorSource("AGENT")
                            .summary(opsMessages.get("ops.approval.backfill", ctx.locale()))
                            .param("dateStart", bfReq.dateStart())
                            .param("dateEnd", bfReq.dateEnd())
                            .param("includeDownstream", bfReq.includeDownstream())
                            .param("parallelism", bfReq.parallelism())
                            .build();
                    GateResult gr = gatedActionService.submit(req, ctx.locale());
                    if (gr.pending() || "DENIED".equals(gr.outcome().name())) return gateText(gr);
                    try {
                        BackfillRun run = dataOpsBridge.submitBackfill(bfReq);
                        return Map.of("outcome", "EXECUTED", "run", run);
                    } catch (UnsupportedOperationException e) {
                        return Map.of("outcome", "EXECUTED", "run", null,
                                "note", "领域执行待 Stream A 实现");
                    }
                });

        register("query_backfill", "查询本租户补数据运行记录（租户隔离）",
                schema(prop("page", "integer", "页码，默认 1"),
                        prop("size", "integer", "每页数量，默认 20"),
                        prop("runId", "string", "单个运行 id（UUID），提供时忽略分页")),
                ctx -> {
                    requireTenant(ctx);
                    String runIdStr = str(ctx.args(), "runId");
                    if (runIdStr != null && !runIdStr.isBlank()) {
                        try {
                            UUID runId = UUID.fromString(runIdStr.trim());
                            BackfillRun run = dataOpsBridge.backfillRun(runId);
                            List<InstanceRow> instances = dataOpsBridge.backfillRunInstances(runId);
                            return Map.of("run", run, "instances", instances);
                        } catch (UnsupportedOperationException e) {
                            return Map.of("note", "待 Stream A 实现");
                        }
                    }
                    Long page = lng(ctx.args(), "page");
                    Long size = lng(ctx.args(), "size");
                    int p = page != null ? page.intValue() : 1;
                    int s = size != null ? size.intValue() : 20;
                    try {
                        List<BackfillRun> runs = dataOpsBridge.backfillRuns(p, s);
                        return Map.of("items", runs, "total", runs.size());
                    } catch (UnsupportedOperationException e) {
                        return Map.of("items", List.of(), "total", 0, "note", "待 Stream A 实现");
                    }
                });
    }

    private Object gateText(GateResult gr) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("outcome", gr.outcome().name());
        r.put("level", gr.level());
        r.put("message", gr.message());
        if (gr.pending()) {
            r.put("approvalId", gr.actionId());
            r.put("requiresConfirmation", gr.requiresConfirmation());
            r.put("summary", gr.summary());
        }
        if (gr.resultInstanceId() != null) {
            r.put("resultInstanceId", gr.resultInstanceId());
        }
        return r;
    }

    // ============================ 截断 + 存档 ============================

    private ToolResult truncateAndArchive(String toolName, String text) {
        if (text == null) {
            return ToolResult.ok("");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= outputThreshold) {
            return ToolResult.ok(text);
        }
        String ref = archive(toolName, text);
        String head = text.substring(0, Math.min(text.length(), outputThreshold));
        String marked = head + "\n\n[truncated] 原始 " + bytes.length + " 字节，完整输出存档："
                + (ref != null ? ref : "(存档失败)");
        return new ToolResult(marked, false, true, ref);
    }

    private String archive(String toolName, String text) {
        try {
            Files.createDirectories(archiveDir);
            Path file = archiveDir.resolve(toolName + "-" + Integer.toHexString(text.hashCode()) + ".txt");
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file.toString();
        } catch (IOException e) {
            return null;
        }
    }

    // ============================ helpers ============================

    /** E1: 解析租户身份 —— 优先从 MCP context，回退 ThreadLocal（测试/直调），两者都无则抛错。 */
    private Long requireTenant(McpTool.Context ctx) {
        Long tenantId = ctx.tenantId() != null ? ctx.tenantId() : TenantContext.tenantId();
        if (tenantId == null) {
            throw new BizException("mcp.tenant_required");
        }
        return tenantId;
    }

    private void register(String name, String description, Map<String, Object> schema,
                          McpTool.Handler handler) {
        tools.put(name, new McpTool(name, description, schema, handler));
    }

    private Map<String, Object> schema(Map<String, Object>... props) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map<String, Object> p : props) {
            String name = (String) p.get("__name");
            boolean isRequired = Boolean.TRUE.equals(p.get("__required"));
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("type", p.get("type"));
            def.put("description", p.get("description"));
            properties.put(name, def);
            if (isRequired) {
                required.add(name);
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private Map<String, Object> prop(String name, String type, String description) {
        return field(name, type, description, false);
    }

    private Map<String, Object> req(String name, String type, String description) {
        return field(name, type, description, true);
    }

    private Map<String, Object> field(String name, String type, String description, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("__name", name);
        m.put("__required", required);
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    private String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private String required(Map<String, Object> args, String key) {
        String v = str(args, key);
        if (v == null || v.isBlank()) {
            throw new BizException("mcp.param_required", key);
        }
        return v;
    }

    private Long lng(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new BizException("mcp.param_int", key);
        }
    }

    private Long requiredLong(Map<String, Object> args, String key) {
        Long v = lng(args, key);
        if (v == null) {
            throw new BizException("mcp.param_required", key);
        }
        return v;
    }

    private java.util.UUID uuid(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        try {
            return java.util.UUID.fromString(v.toString().trim());
        } catch (IllegalArgumentException e) {
            throw new BizException("mcp.param_uuid", key);
        }
    }

    private java.util.UUID requiredUuid(Map<String, Object> args, String key) {
        java.util.UUID v = uuid(args, key);
        if (v == null) {
            throw new BizException("mcp.param_required", key);
        }
        return v;
    }
}
