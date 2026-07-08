package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ProjectScope;
import com.dataweave.master.application.authoring.AuthoringContext;
import com.dataweave.master.application.authoring.AuthoringContextService;
import com.dataweave.master.application.authoring.TaskDependencyView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 058 数据开发 LSP 创作上下文 REST（契约 rest-analyze.md，US1）。
 * 基址 /api/authoring-context（JWT + X-Project-Id/?projectId=，经 {@link ProjectScope#require} 成员校验）。
 *
 * <ul>
 *   <li>{@code GET /{taskDefId}} —— 已 push 任务的创作上下文包（读写表→上下游 + 表/列血缘 + 三态接地）</li>
 *   <li>{@code GET /{taskDefId}/deps} —— 任务依赖视图（声明 DAG + 推导血缘合并带 origin）</li>
 *   <li>{@code POST /analyze} —— 按 taskRef 分析（工作副本草稿的整体分析随 T012 扩展）</li>
 * </ul>
 * 纯读、确定性、零 LLM；depth 缺省=多跳（由 LineageQueryService.clampDepth 收敛）。
 */
@RestController
@RequestMapping("/api/authoring-context")
public class AuthoringContextController {

    private final AuthoringContextService service;
    private final ProjectScope projectScope;

    public AuthoringContextController(AuthoringContextService service, ProjectScope projectScope) {
        this.service = service;
        this.projectScope = projectScope;
    }

    /** projectId 优先取请求显式参数 → 回退 TenantContext → ProjectScope.require 成员校验。 */
    private long resolveProjectId(Long requestProjectId) {
        Long pid = requestProjectId != null ? requestProjectId : TenantContext.projectId();
        return projectScope.require(TenantContext.tenantId(), TenantContext.userId(), pid);
    }

    @GetMapping("/{taskDefId}")
    public ApiResponse<AuthoringContext> context(@PathVariable long taskDefId,
                                                 @RequestParam(required = false) Integer depth,
                                                 @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(service.context(
                TenantContext.tenantId(), pid, String.valueOf(taskDefId), depth != null ? depth : 0));
    }

    @GetMapping("/{taskDefId}/deps")
    public ApiResponse<TaskDependencyView> deps(@PathVariable long taskDefId,
                                                @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(service.taskDependencies(TenantContext.tenantId(), pid, taskDefId));
    }

    @PostMapping("/analyze")
    public ApiResponse<AuthoringContext> analyze(@RequestBody AnalyzeRequest req,
                                                 @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        long tenant = TenantContext.tenantId();
        int depth = req.depth() != null ? req.depth() : 0;
        // 带 content=工作副本草稿分析（不落库、零持久化，T012/FR-004）；否则按 taskRef 分析已 push 任务
        if (req.content() != null) {
            var draft = new AuthoringContextService.DraftInput(
                    req.taskRef(), req.type(), req.content(), req.datasourceId(), req.targetDatasourceId());
            return ApiResponse.ok(service.contextForDraft(tenant, pid, draft, depth));
        }
        return ApiResponse.ok(service.context(tenant, pid, req.taskRef(), depth));
    }

    /**
     * analyze 请求体。带 {@code content}=工作副本草稿（type/datasourceId/targetDatasourceId 随附）；
     * 仅 {@code taskRef}=分析已 push 任务。depth 缺省多跳。
     */
    public record AnalyzeRequest(String taskRef, Integer depth, String type, String content,
                                 Long datasourceId, Long targetDatasourceId) {}
}
