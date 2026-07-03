package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.ProjectScope;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.incident.IncidentService;
import com.dataweave.master.domain.incident.Incident;
import com.dataweave.master.i18n.BizException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Incident 监督席 REST 端点（043）：队列、历史、详情、处置（重跑/静默/备注）。
 *
 * <p>全部端点 JWT + {@link ProjectScope#require}（036 规约）。
 * 跨项目访问 id → {@code incident.not_found}。错误一律 {@link BizException(code, args)}。
 *
 * <p>契约参见 specs/043-incident-queue/contracts/incident-api.md §1-6。
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final GatedActionService gatedActionService;
    private final ProjectScope projectScope;

    public IncidentController(IncidentService incidentService,
                              GatedActionService gatedActionService,
                              ProjectScope projectScope) {
        this.incidentService = incidentService;
        this.gatedActionService = gatedActionService;
        this.projectScope = projectScope;
    }

    private Long resolveProjectId(Long requestProjectId) {
        Long pid = requestProjectId != null ? requestProjectId : TenantContext.projectId();
        return projectScope.require(TenantContext.tenantId(), TenantContext.userId(), pid);
    }

    // ═══════════════════════════════════════════════════════════
    // §1 GET /api/incidents — 监督席队列（三区）
    // ═══════════════════════════════════════════════════════════

    @GetMapping
    public ApiResponse<Map<String, Object>> queue(@RequestParam Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(incidentService.queue(TenantContext.tenantId(), pid));
    }

    // ═══════════════════════════════════════════════════════════
    // §2 GET /api/incidents/history — 历史筛选分页
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/history")
    public ApiResponse<Map<String, Object>> history(
            @RequestParam Long projectId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String signature,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(incidentService.history(TenantContext.tenantId(), pid,
                state, signature, from, to, page, size));
    }

    // ═══════════════════════════════════════════════════════════
    // §3 GET /api/incidents/{id} — 详情 + 时间线 + 关联动作
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        Map<String, Object> detail = incidentService.detail(id);
        Incident inc = (Incident) detail.get("incident");
        if (inc == null) throw new BizException("incident.not_found", id);
        // 跨项目守卫：实体 projectId 必须匹配当前请求 projectId
        resolveProjectId(inc.getProjectId());
        return ApiResponse.ok(detail);
    }

    // ═══════════════════════════════════════════════════════════
    // §4 POST /api/incidents/{id}/rerun — 闸门化重跑（FR-009）
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/{id}/rerun")
    public ApiResponse<RerunResult> rerun(@PathVariable long id,
                                          @RequestBody RerunRequest body) {
        Map<String, Object> detail = incidentService.detail(id);
        Incident inc = (Incident) detail.get("incident");
        if (inc == null) throw new BizException("incident.not_found", id);

        // 归属校验：当前请求项目必须匹配工单归属项目
        resolveProjectId(inc.getProjectId());

        // 校验 taskInstanceId 属于该工单来源
        String taskInstanceId = body.taskInstanceId().toString();
        if (!"TASK".equals(inc.getSourceKind())) {
            throw new BizException("incident.invalid_state", inc.getState());
        }

        // 构造闸门 ActionRequest（镜像 McpToolRegistry.task_rerun，actorSource=UI）
        ActionRequest req = ActionRequest.builder()
                .toolName("incident_rerun").actionType("TASK_RERUN")
                .targetType("TASK_INSTANCE").targetId(taskInstanceId)
                .actor(String.valueOf(TenantContext.userId())).actorSource("UI")
                .incidentId(id)
                .summary("Incident #" + id + " 重跑实例 #" + taskInstanceId)
                .build();

        GateResult result = gatedActionService.submit(req);

        // 追加 ACTION 时间线 + CAS OPEN → MITIGATING
        String actionPayload = incidentService.toJson(Map.of(
                "actionId", result.actionId(),
                "outcome", result.outcome().name(),
                "level", result.level(),
                "taskInstanceId", taskInstanceId));
        incidentService.appendTimeline(id, "ACTION", actionPayload,
                String.valueOf(TenantContext.userId()));
        incidentService.markMitigating(id);

        return ApiResponse.ok(new RerunResult(
                result.outcome().name(), result.actionId(), result.message()));
    }

    // ═══════════════════════════════════════════════════════════
    // §5 suppress / unsuppress
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/{id}/suppress")
    public ApiResponse<Void> suppress(@PathVariable long id,
                                      @RequestBody SuppressRequest body) {
        Map<String, Object> detail = incidentService.detail(id);
        Incident inc = (Incident) detail.get("incident");
        if (inc == null) throw new BizException("incident.not_found", id);
        resolveProjectId(inc.getProjectId());

        if (body.reason() == null || body.reason().isBlank()) {
            throw new BizException("incident.suppress_reason_required");
        }
        incidentService.suppress(id, body.reason(),
                String.valueOf(TenantContext.userId()));
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/unsuppress")
    public ApiResponse<Void> unsuppress(@PathVariable long id) {
        Map<String, Object> detail = incidentService.detail(id);
        Incident inc = (Incident) detail.get("incident");
        if (inc == null) throw new BizException("incident.not_found", id);
        resolveProjectId(inc.getProjectId());
        incidentService.unsuppress(id, String.valueOf(TenantContext.userId()));
        return ApiResponse.ok();
    }

    // ═══════════════════════════════════════════════════════════
    // §6 notes
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/{id}/notes")
    public ApiResponse<Void> notes(@PathVariable long id,
                                   @RequestBody NoteRequest body) {
        Map<String, Object> detail = incidentService.detail(id);
        Incident inc = (Incident) detail.get("incident");
        if (inc == null) throw new BizException("incident.not_found", id);
        resolveProjectId(inc.getProjectId());

        if (body.text() == null || body.text().isBlank()) {
            throw new BizException("incident.invalid_state", inc.getState());
        }
        incidentService.appendNote(id, body.text(),
                String.valueOf(TenantContext.userId()));
        return ApiResponse.ok();
    }

    // ═══════════════════════════════════════════════════════════
    // DTO records（契约 shapes = contracts/incident-api.md §4-6）
    // ═══════════════════════════════════════════════════════════

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RerunRequest(UUID taskInstanceId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SuppressRequest(String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NoteRequest(String text) {}

    /** 闸门重跑结果（contract §4）。 */
    public record RerunResult(String outcome, Long actionId, String message) {}
}
