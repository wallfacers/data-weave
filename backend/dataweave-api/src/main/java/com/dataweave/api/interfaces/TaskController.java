package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.CatalogAssignService;
import com.dataweave.master.application.CatalogException;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.ScheduleParamResolver;
import com.dataweave.master.application.TaskService;
import com.dataweave.master.application.TaskService.PageResult;
import com.dataweave.master.application.TaskService.TaskDetail;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.i18n.BizException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

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

    public TaskController(TaskService taskService, ScheduleParamResolver paramResolver,
                          CatalogAssignService catalogAssignService,
                          GatedActionService gatedActionService) {
        this.taskService = taskService;
        this.paramResolver = paramResolver;
        this.catalogAssignService = catalogAssignService;
        this.gatedActionService = gatedActionService;
    }

    /** 创建任务草稿。 */
    @PostMapping
    public ApiResponse<TaskDef> create(@RequestBody TaskDef body) {
        return ApiResponse.ok(taskService.create(body));
    }

    /** 分页搜索任务（可选类目/标签过滤；无新参数时行为与既有一致）。 */
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        LocalDateTime start = parseDateTime(startTime);
        LocalDateTime end = parseDateTime(endTime);
        return ApiResponse.ok(taskService.search(keyword, type, status, start, end,
                catalogNodeId, uncategorized, tagId, page, size));
    }

    /**
     * 归类任务：{@code {"catalogNodeId": null}} 清空归属、{@code {}}（字段缺失）不改、给值则归入。
     * {@code path} 字段一律拒收。
     */
    @PatchMapping("/{id}/catalog")
    public ApiResponse<Void> assignCatalog(@PathVariable Long id, @RequestBody Map<String, Object> body) {
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

    /** 更新任务（仅 DRAFT 可改）。 */
    @PutMapping("/{id}")
    public ApiResponse<TaskDef> update(@PathVariable Long id, @RequestBody TaskDef body) {
        return ApiResponse.ok(taskService.update(id, body));
    }

    /** 软删除任务（仅 DRAFT 可删）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> softDelete(@PathVariable Long id) {
        taskService.softDelete(id);
        return ApiResponse.ok();
    }

    /** 发布上线。 */
    @PostMapping("/{id}/publish")
    public ApiResponse<TaskDef> publish(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, String> body) {
        String remark = body != null ? body.get("remark") : null;
        return ApiResponse.ok(taskService.publish(id, remark));
    }

    /** 下线。 */
    @PostMapping("/{id}/offline")
    public ApiResponse<TaskDef> offline(@PathVariable Long id) {
        return ApiResponse.ok(taskService.offline(id));
    }

    /**
     * 手动触发正式运行（manual-run-trigger）：对**已发布**任务经闸门起一个 run_mode=NORMAL 的
     * 正式 task_instance，计入运维统计。返回 {@link GateResult}：
     * <ul>
     *   <li>{@code EXECUTED} —— {@code data.resultInstanceId} 为新实例 id，前端接日志流；</li>
     *   <li>{@code PENDING_APPROVAL} —— {@code data.actionId} 为审批单号，批准后才下发。</li>
     * </ul>
     * 仅草稿/未发布任务在闸门前即拒（409）；触发不触碰 cron 计划。
     */
    @PostMapping("/{id}/run")
    public ApiResponse<GateResult> run(@PathVariable Long id,
                                       @RequestBody(required = false) RunRequest body) {
        TaskDetail detail = taskService.getById(id).orElse(null);
        if (detail == null) {
            throw new BizException("task.not_found", id).withHttpStatus(404);
        }
        if (!"ONLINE".equals(detail.task().getStatus())) {
            throw new BizException("task.not_published").withHttpStatus(409);
        }
        ActionRequest req = ActionRequest.builder()
                .toolName("run_task").actionType("TASK_RUN")
                .targetType("TASK").targetId(String.valueOf(id))
                .command(body != null ? body.bizDate() : null)
                .actor("ui").actorSource("UI")
                .summary("手动运行任务 #" + id + "「" + detail.task().getName() + "」")
                .build();
        return ApiResponse.ok(gatedActionService.submit(req));
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

    /** 手动运行请求体（bizDate 可空）。 */
    public record RunRequest(String bizDate) {
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
