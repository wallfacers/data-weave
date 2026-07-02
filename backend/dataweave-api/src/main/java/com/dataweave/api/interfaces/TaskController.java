package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.Locales;
import com.dataweave.api.infrastructure.ProjectAuthz;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.CatalogAssignService;
import com.dataweave.master.application.CatalogException;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.ScheduleParamResolver;
import com.dataweave.master.application.TaskService;
import com.dataweave.master.application.TestRunCommand;
import com.dataweave.master.application.TaskService.PageResult;
import com.dataweave.master.application.TaskService.TaskDetail;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.i18n.BizException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 任务 CRUD REST 端点。
 *
 * <p>支持创建草稿、分页搜索、查看详情、编辑（仅 DRAFT）、软删除、发布上线、下线。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskService taskService;
    private final ScheduleParamResolver paramResolver;
    private final CatalogAssignService catalogAssignService;
    private final GatedActionService gatedActionService;
    private final ProjectAuthz projectAuthz;

    public TaskController(TaskService taskService, ScheduleParamResolver paramResolver,
                          CatalogAssignService catalogAssignService,
                          GatedActionService gatedActionService,
                          ProjectAuthz projectAuthz) {
        this.taskService = taskService;
        this.paramResolver = paramResolver;
        this.catalogAssignService = catalogAssignService;
        this.gatedActionService = gatedActionService;
        this.projectAuthz = projectAuthz;
    }

    /** 创建任务草稿。036-D：EDITOR+（task:manage），并按当前项目打戳（FR-042）。 */
    @PostMapping
    public ApiResponse<TaskDef> create(@RequestBody TaskDef body) {
        projectAuthz.requireCurrent("task:manage");
        body.setTenantId(TenantContext.tenantId());
        body.setProjectId(TenantContext.projectId());
        return ApiResponse.ok(taskService.create(body));
    }

    /** 分页搜索任务（可选类目/标签/负责人/冻结/数据源过滤；无新参数时行为与既有一致）。 */
    @GetMapping
    public ApiResponse<PageResult> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) Long catalogNodeId,
            @RequestParam(defaultValue = "false") boolean uncategorized,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) Integer frozen,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        LocalDateTime start = parseDateTime(startTime);
        LocalDateTime end = parseDateTime(endTime);
        // ownerId=me 解析为当前用户 ID
        Long resolvedOwnerId = null;
        if ("me".equalsIgnoreCase(ownerId)) {
            resolvedOwnerId = TenantContext.userId();
        } else if (ownerId != null && !ownerId.isBlank()) {
            try {
                resolvedOwnerId = Long.parseLong(ownerId);
            } catch (NumberFormatException ignored) {
                // 非数字忽略，不按 owner 过滤
            }
        }
        return ApiResponse.ok(taskService.search(keyword, type, status, start, end,
                catalogNodeId, uncategorized, tagId, resolvedOwnerId, frozen, datasourceId,
                page, size));
    }

    /**
     * 归类任务：{@code {"catalogNodeId": null}} 清空归属、{@code {}}（字段缺失）不改、给值则归入。
     * {@code path} 字段一律拒收。
     */
    @PatchMapping("/{id}/catalog")
    public ApiResponse<Void> assignCatalog(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireTaskManage(id);
        if (body.containsKey("path")) {
            throw new CatalogException("catalog.path.derived");
        }
        if (body.containsKey("catalogNodeId")) {
            Object v = body.get("catalogNodeId");
            Long nodeId = v != null ? ((Number) v).longValue() : null;
            catalogAssignService.assignTask(id, nodeId);
        }
        return ApiResponse.ok();
    }

    /** 获取任务详情（含版本历史）。 */
    @GetMapping("/{id}")
    public ApiResponse<TaskDetail> getById(@PathVariable Long id) {
        TaskDetail detail = taskService.getById(id).orElse(null);
        if (detail == null) {
            throw new BizException("task.not_found", id).withHttpStatus(404);
        }
        return ApiResponse.ok(detail);
    }

    /** 更新任务（仅 DRAFT 可改）。036-D：EDITOR+（task:manage），按实体归属 projectId 授权。 */
    @PutMapping("/{id}")
    public ApiResponse<TaskDef> update(@PathVariable Long id, @RequestBody TaskDef body) {
        requireTaskManage(id);
        return ApiResponse.ok(taskService.update(id, body));
    }

    /** 软删除任务（仅 DRAFT 可删）。036-D：EDITOR+（task:manage），按实体归属 projectId 授权。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> softDelete(@PathVariable Long id) {
        requireTaskManage(id);
        taskService.softDelete(id);
        return ApiResponse.ok();
    }

    /** 发布上线。036-D：EDITOR+（task:manage），按实体归属 projectId 授权。 */
    @PostMapping("/{id}/publish")
    public ApiResponse<TaskDef> publish(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, String> body) {
        requireTaskManage(id);
        String remark = body != null ? body.get("remark") : null;
        return ApiResponse.ok(taskService.publish(id, remark));
    }

    /** 下线。036-D：EDITOR+（task:manage），按实体归属 projectId 授权。 */
    @PostMapping("/{id}/offline")
    public ApiResponse<TaskDef> offline(@PathVariable Long id) {
        requireTaskManage(id);
        return ApiResponse.ok(taskService.offline(id));
    }

    /** 回滚到历史版本（恢复为草稿，需手动再发布）。经闸门 L1 直执行 + agent_action 留痕。
     * 036-D：EDITOR+（task:manage），按实体归属 projectId 授权（闸门前置门）。 */
    @PostMapping("/{id}/rollback")
    public ApiResponse<GateResult> rollback(@PathVariable Long id,
                                            @RequestBody Map<String, Object> body,
                                            ServerWebExchange exchange) {
        requireTaskManage(id);
        TaskDetail detail = taskService.getById(id).orElse(null);
        if (detail == null) {
            throw new BizException("task.not_found", id).withHttpStatus(404);
        }
        Object vno = body.get("versionNo");
        if (vno == null) {
            throw new BizException("task.rollback.missing_version_no").withHttpStatus(400);
        }
        ActionRequest req = ActionRequest.builder()
                .toolName("rollback_task").actionType("ROLLBACK_TASK")
                .targetType("TASK").targetId(String.valueOf(id))
                .command(String.valueOf(vno))
                .actor("ui").actorSource("UI")
                .summary("回滚任务 #" + id + "「" + detail.task().getName() + "」到 v" + vno)
                .build();
        return ApiResponse.ok(gatedActionService.submit(req,
                Locales.uiLocale(exchange.getRequest().getHeaders())));
    }

    /**
     * 手动运行任务（task-run-decouple）：按发布态分流，不再对未发布任务拒绝（解绑「发布」与「运行」）。
     * <ul>
     *   <li>**已发布（ONLINE）** → {@code TASK_RUN}：起 run_mode=NORMAL 正式实例，跑已发布版本快照，计入统计；</li>
     *   <li>**未发布（DRAFT）** → {@code TEST_RUN}：起 run_mode=TEST 测试实例，跑请求体携带的**编辑器当前内容**
     *       （含未保存改动，经 content_override 不落 task_def），不计统计。</li>
     * </ul>
     * 两路均经闸门（默认 L1 直执行 + agent_action 留痕）。返回 {@link GateResult}：
     * {@code EXECUTED}→{@code data.resultInstanceId}（前端接日志流）；{@code PENDING_APPROVAL}→{@code data.actionId}。
     * 触发不触碰 cron 计划。
     */
    @PostMapping("/{id}/run")
    public ApiResponse<GateResult> run(@PathVariable Long id,
                                       @RequestBody(required = false) RunRequest body,
                                       ServerWebExchange exchange) {
        TaskDetail detail = taskService.getById(id).orElse(null);
        if (detail == null) {
            throw new BizException("task.not_found", id).withHttpStatus(404);
        }
        boolean published = "ONLINE".equals(detail.task().getStatus());
        String bizDate = body != null ? body.bizDate() : null;
        ActionRequest.Builder b = ActionRequest.builder()
                .targetType("TASK").targetId(String.valueOf(id))
                .actor("ui").actorSource("UI");
        if (published) {
            // 正式运行：跑已发布版本快照，忽略编辑器临时内容；command 仅 bizDate。
            b.toolName("run_task").actionType("TASK_RUN")
                    .command(bizDate)
                    .summary("手动运行任务 #" + id + "「" + detail.task().getName() + "」");
        } else {
            // 测试运行：跑编辑器当前内容（不管存没存），经 TestRunCommand 编码携带；不落 task_def。
            String encoded = TestRunCommand.encode(bizDate,
                    body != null ? body.content() : null,
                    body != null ? body.paramsJson() : null,
                    body != null ? body.type() : null);
            b.toolName("test_run").actionType("TEST_RUN")
                    .command(encoded)
                    .summary("测试运行任务 #" + id + "「" + detail.task().getName() + "」");
        }
        return ApiResponse.ok(gatedActionService.submit(b.build(),
                Locales.uiLocale(exchange.getRequest().getHeaders())));
    }

    /**
     * 预览调度参数占位符替换（任务编辑器用）：把 content 里的 {@code ${...}} / 内置参数
     * 按 bizDate + paramsJson 替换为具体值返回。解析失败返回 400 + 占位符名。
     */
    @PostMapping("/preview-params")
    public ApiResponse<Map<String, String>> previewParams(@RequestBody PreviewRequest body) {
        try {
            var ctx = new ScheduleParamResolver.BuiltInContext(null, null, null, LocalDate.now());
            String resolved = paramResolver.resolve(
                    body.content() == null ? "" : body.content(),
                    body.bizDate(),
                    body.paramsJson(),
                    ctx);
            return ApiResponse.ok(Map.of("content", resolved));
        } catch (ScheduleParamResolver.UnresolvedPlaceholderException e) {
            // UnresolvedPlaceholderException 本身即 BizException（携带 schedule.placeholder.* code + 占位符名 args），
            // 直接上抛由 GlobalExceptionHandler 按请求 locale 本地化，无需在此预渲染
            throw e;
        }
    }

    /** 预览请求体。 */
    public record PreviewRequest(String content, String bizDate, String paramsJson) {
    }

    /**
     * 手动运行请求体（全部字段可空）。{@code content}/{@code type}/{@code paramsJson} 为测试运行携带的
     * 编辑器当前内容（含未保存改动），仅未发布任务的 TEST 路径消费；已发布任务忽略，跑发布版本快照。
     */
    public record RunRequest(String bizDate, String content, String type, String paramsJson) {
    }

    /**
     * 036-D：任务定义写操作授权（FR-042 EDITOR+）。按实体归属 projectId 授权，
     * 防跨项目按 id 改删；任务不存在时保持既有 404 语义。
     */
    private void requireTaskManage(Long taskId) {
        Long pid = taskService.getById(taskId)
                .map(d -> d.task().getProjectId())
                .orElseThrow(() -> new BizException("task.not_found", taskId).withHttpStatus(404));
        projectAuthz.require("task:manage", pid);
    }

    private LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s, DT_FMT);
        } catch (Exception e) {
            return null;
        }
    }
}
