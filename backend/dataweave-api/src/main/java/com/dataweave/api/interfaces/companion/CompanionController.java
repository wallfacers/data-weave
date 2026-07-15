package com.dataweave.api.interfaces.companion;

import java.time.LocalDateTime;
import java.util.List;

import com.dataweave.api.application.DisplayNameResolver;
import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.Locales;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ProjectScope;
import com.dataweave.master.companion.application.CompanionChatService;
import com.dataweave.master.companion.application.PatrolService;
import com.dataweave.master.companion.application.ReportService;
import com.dataweave.master.companion.domain.MessageView;
import com.dataweave.master.companion.domain.ReportView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * 管家 REST 端点（{@code /api/companion}）。
 * 汇报面（T020）：列表 / 项目级关闭 / 标记已读；对话面（T024）：chat / cancel / messages；
 * trigger 手动触发（US2 测试机制 + US4 治理共享）。治理面 routines GET/PATCH/runs（T029）后续追加。
 * SSE 流见 {@link CompanionStreamHandler}；REST 统一 {@code 200 + ApiResponse(code===0)}。
 */
@RestController
@RequestMapping("/api/companion")
public class CompanionController {

    private final ReportService reportService;
    private final PatrolService patrolService;
    private final CompanionChatService chatService;
    private final ProjectScope projectScope;
    private final DisplayNameResolver displayNameResolver;

    public CompanionController(ReportService reportService, PatrolService patrolService,
                               CompanionChatService chatService, ProjectScope projectScope,
                               DisplayNameResolver displayNameResolver) {
        this.reportService = reportService;
        this.patrolService = patrolService;
        this.chatService = chatService;
        this.projectScope = projectScope;
        this.displayNameResolver = displayNameResolver;
    }

    @GetMapping("/reports")
    public ApiResponse<List<ReportView>> reports(@RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "50") int limit,
                                                 @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(reportService.list(TenantContext.tenantId(), pid, status, limit));
    }

    /** 项目级关闭（幂等；一人关闭全员消失，经 SSE report:closed 同步）。 */
    @PostMapping("/reports/{id}/close")
    public ApiResponse<ReportView> close(@PathVariable long id,
                                         @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(reportService.close(TenantContext.tenantId(), pid, id, currentActorName()));
    }

    /** 标记已读（未读计数）。 */
    @PostMapping("/reports/{id}/read")
    public ApiResponse<ReportView> read(@PathVariable long id,
                                        @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(reportService.read(TenantContext.tenantId(), pid, id));
    }

    /** 手动触发一轮巡检（US4 治理 + US2 测试机制）；返回 runId，异步执行。 */
    @PostMapping("/routines/{id}/trigger")
    public ApiResponse<TriggerResult> trigger(@PathVariable long id,
                                              @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        long runId = patrolService.triggerManual(TenantContext.tenantId(), pid, id);
        return ApiResponse.ok(new TriggerResult(runId));
    }

    /** 对话：服务端认定 actor；reportId 非空锚定该汇报上下文；回流走 SSE message/delta/end。 */
    @PostMapping("/chat")
    public ApiResponse<MessageView> chat(@RequestBody ChatRequest body,
                                         @RequestParam(required = false) Long projectId,
                                         ServerWebExchange exchange) {
        long pid = resolveProjectId(projectId);
        MessageView msg = chatService.chat(TenantContext.tenantId(), pid,
                body == null ? null : body.reportId(), body == null ? null : body.content(),
                currentActor(), currentActorName(), Locales.uiLocale(exchange.getRequest().getHeaders()));
        return ApiResponse.ok(msg);
    }

    /** 打断当前会话流式输出（L0 免审批；1s 内 end{interrupted:true}）。 */
    @PostMapping("/chat/cancel")
    public ApiResponse<CancelResult> cancel(@RequestBody(required = false) CancelRequest body,
                                            @RequestParam(required = false) Long projectId,
                                            ServerWebExchange exchange) {
        long pid = resolveProjectId(projectId);
        boolean cancelled = chatService.cancel(TenantContext.tenantId(), pid,
                body == null ? null : body.reportId(), currentActor(),
                Locales.uiLocale(exchange.getRequest().getHeaders()));
        return ApiResponse.ok(new CancelResult(cancelled));
    }

    /** 历史消息（全局或锚定会话）。 */
    @GetMapping("/messages")
    public ApiResponse<List<MessageView>> messages(@RequestParam(required = false) Long reportId,
                                                   @RequestParam(required = false)
                                                   @DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime before,
                                                   @RequestParam(defaultValue = "200") int limit,
                                                   @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(chatService.history(TenantContext.tenantId(), pid, reportId, before, limit));
    }

    /** 手动触发结果。 */
    public record TriggerResult(long runId) {}

    public record ChatRequest(String content, Long reportId) {}

    public record CancelRequest(Long reportId) {}

    public record CancelResult(boolean cancelled) {}

    private long resolveProjectId(Long requestProjectId) {
        Long pid = requestProjectId != null ? requestProjectId : TenantContext.projectId();
        return projectScope.require(TenantContext.tenantId(), TenantContext.userId(), pid);
    }

    /** 服务端认定的发言者标识（username）。 */
    protected String currentActor() {
        String username = TenantContext.username();
        return username != null && !username.isBlank() ? username : "user";
    }

    /** 服务端解析的显示名（displayName），查不到回退 username。 */
    protected String currentActorName() {
        return displayNameResolver.resolve(TenantContext.userId(), currentActor());
    }
}
