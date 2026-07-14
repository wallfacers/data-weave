package com.dataweave.api.interfaces;

import java.util.List;
import java.util.UUID;

import java.util.ArrayList;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.Locales;
import com.dataweave.api.infrastructure.ProjectAuthz;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ApprovalService;
import com.dataweave.master.application.OpsContracts.PageResult;
import com.dataweave.master.application.ProjectRoleService;
import com.dataweave.master.application.ProjectScope;
import com.dataweave.master.application.incident.IncidentAgentService;
import com.dataweave.master.application.incident.IncidentBriefingService;
import com.dataweave.master.application.incident.IncidentBriefingService.BriefingView;
import com.dataweave.master.application.incident.IncidentConversationService;
import com.dataweave.master.application.incident.IncidentEventPublisher;
import com.dataweave.master.application.incident.IncidentQueryService;
import com.dataweave.master.application.incident.IncidentQueryService.Detail;
import com.dataweave.master.application.incident.IncidentQueryService.Snapshot;
import com.dataweave.master.application.lineage.agent.AgentLineageConfigService;
import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.incident.Incident;
import com.dataweave.master.domain.incident.IncidentMessage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

/**
 * 069 事故域 REST（契约：specs/069-agent-incident-ops/contracts/incident-api.md）。
 * 基址 /api/incidents，项目隔离经 {@link ProjectScope#require}；智能运维开关走同一基址下的
 * /agent-config 子路径（租户级，复用 053 lineage_agent_config 行，仅切 ops_enabled）。
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentQueryService queryService;
    private final AgentLineageConfigService agentConfigService;
    private final ProjectScope projectScope;
    private final ProjectRoleService projectRoleService;
    private final IncidentAgentService agentService;
    private final ProjectAuthz projectAuthz;
    private final IncidentConversationService conversationService;
    private final IncidentBriefingService briefingService;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IncidentController(IncidentQueryService queryService, AgentLineageConfigService agentConfigService,
                              ProjectScope projectScope, ProjectRoleService projectRoleService,
                              IncidentAgentService agentService, ProjectAuthz projectAuthz,
                              IncidentConversationService conversationService, IncidentBriefingService briefingService,
                              EventBus eventBus) {
        this.queryService = queryService;
        this.agentConfigService = agentConfigService;
        this.projectScope = projectScope;
        this.projectRoleService = projectRoleService;
        this.agentService = agentService;
        this.projectAuthz = projectAuthz;
        this.conversationService = conversationService;
        this.briefingService = briefingService;
        this.eventBus = eventBus;
    }

    @GetMapping
    public ApiResponse<PageResult<Incident>> list(@RequestParam(required = false) List<String> state,
                                                    @RequestParam(required = false) Long taskDefId,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(queryService.list(TenantContext.tenantId(), pid, state, taskDefId, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@PathVariable UUID id, @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(queryService.detail(TenantContext.tenantId(), pid, id));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<IncidentMessage>> messages(@PathVariable UUID id,
                                                         @RequestParam(defaultValue = "0") long afterSeq,
                                                         @RequestParam(defaultValue = "200") int limit,
                                                         @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(queryService.messages(TenantContext.tenantId(), pid, id, afterSeq, limit));
    }

    /**
     * T028 指挥中心直播流：建连先发 snapshot（未收口事故 + 实时数字）→ 可选 Last-Event-ID 补齐持久化消息 →
     * 桥接 EventBus 频道 {@code dw:incident:evt:{projectId}} 实时事件。瞬态事件（thinking/chip/delta）断线不重放。
     * 鉴权经 query token+projectId（{@code JwtAuthFilter} 已处理，EventSource 无自定义 header）。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam(required = false) Long projectId,
                                                 @RequestParam(name = "lastEventId", required = false) String lastEventIdParam,
                                                 ServerWebExchange exchange) {
        long tenantId = TenantContext.tenantId();
        long pid = resolveProjectId(projectId);
        String lastEventId = lastEventIdParam != null ? lastEventIdParam
                : exchange.getRequest().getHeaders().getFirst("Last-Event-ID");

        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();
        var subscription = eventBus.subscribe(IncidentEventPublisher.channel(pid),
                message -> sink.tryEmitNext(toSse(message)));

        Flux<ServerSentEvent<String>> snapshot = Flux.defer(() -> Flux.fromIterable(snapshotEvents(tenantId, pid)));
        Flux<ServerSentEvent<String>> replay = Flux.defer(() -> Flux.fromIterable(replayEvents(tenantId, pid, lastEventId)));

        return Flux.concat(snapshot, replay, sink.asFlux())
                .doFinally(s -> {
                    try {
                        subscription.close();
                    } catch (Exception e) {
                        // 忽略
                    }
                });
    }

    /** 把 EventBus 上的 Envelope JSON（{event,data}）映射为带正确 event 名的 SSE 帧；delta 事件带 id 供续传锚定。 */
    private ServerSentEvent<String> toSse(String envelopeJson) {
        try {
            var node = objectMapper.readTree(envelopeJson);
            String event = node.hasNonNull("event") ? node.get("event").asString() : "message";
            String data = node.has("data") ? node.get("data").toString() : "{}";
            ServerSentEvent.Builder<String> b = ServerSentEvent.<String>builder().event(event).data(data);
            // message 事件用 incidentId:seq 作 SSE id，供前端 Last-Event-ID 续传锚定
            if ("message".equals(event) && node.has("data")) {
                var d = node.get("data");
                if (d.has("incidentId") && d.has("message") && d.get("message").has("seq")) {
                    b.id(d.get("incidentId").asString() + ":" + d.get("message").get("seq").asLong());
                }
            }
            return b.build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder().event("message").data(envelopeJson).build();
        }
    }

    private List<ServerSentEvent<String>> snapshotEvents(long tenantId, long pid) {
        try {
            Snapshot snap = queryService.snapshot(tenantId, pid);
            String data = "{\"incidents\":" + objectMapper.writeValueAsString(snap.incidents())
                    + ",\"briefingStats\":" + queryService.statsJson(snap.stats()) + "}";
            return List.of(ServerSentEvent.<String>builder().event("snapshot").data(data).build());
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Last-Event-ID=incidentId:seq 时补齐该事故落库消息（瞬态不重放）；缺失/非法则不补。 */
    private List<ServerSentEvent<String>> replayEvents(long tenantId, long pid, String lastEventId) {
        if (lastEventId == null || !lastEventId.contains(":")) {
            return List.of();
        }
        try {
            int idx = lastEventId.lastIndexOf(':');
            UUID incidentId = UUID.fromString(lastEventId.substring(0, idx));
            long afterSeq = Long.parseLong(lastEventId.substring(idx + 1));
            List<IncidentMessage> missed = queryService.messages(tenantId, pid, incidentId, afterSeq, 500);
            List<ServerSentEvent<String>> out = new ArrayList<>();
            for (IncidentMessage m : missed) {
                String data = "{\"incidentId\":\"" + incidentId + "\",\"message\":"
                        + objectMapper.writeValueAsString(m) + "}";
                out.add(ServerSentEvent.<String>builder().event("message")
                        .id(incidentId + ":" + m.seq()).data(data).build());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** T031 战况播报：一句话综述 + 完整接班报告；数字永远 SQL 实时算（SC-010）。 */
    @GetMapping("/briefing")
    public ApiResponse<BriefingView> briefing(@RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        return ApiResponse.ok(briefingService.get(TenantContext.tenantId(), pid));
    }

    /** T030 事故线程对话：落 HUMAN_SAY 即时回显，Agent 回复经直播流达（delta→AGENT_SAY）。 */
    @PostMapping("/{id}/chat")
    public ApiResponse<IncidentMessage> chat(@PathVariable UUID id, @RequestBody(required = false) ChatRequest body,
                                             @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        String actor = body != null ? body.actor() : null;
        IncidentMessage human = conversationService.chat(TenantContext.tenantId(), pid, id,
                body != null ? body.text() : null, actor);
        return ApiResponse.ok(human);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatRequest(String text, String actor) {
    }

    /**
     * 修复提案批准（T023）：036-D2 审批=OWNER only（project:manage）。底层复用 {@link ApprovalService}
     * （L3 需二次确认目标对象名=proposalId）→ 执行器完成基线陈旧校验+发布+重跑验证。
     */
    @PostMapping("/{id}/proposals/{proposalId}/approve")
    public ApiResponse<ApprovalService.ApprovalResult> approveProposal(@PathVariable UUID id, @PathVariable UUID proposalId,
                                                                        @RequestBody(required = false) ProposalDecisionRequest body,
                                                                        @RequestParam(required = false) Long projectId,
                                                                        ServerWebExchange exchange) {
        long pid = resolveProjectId(projectId);
        queryService.detail(TenantContext.tenantId(), pid, id); // 归属核验（非本项目事故一律不存在）
        projectAuthz.require("project:manage", pid);
        String approver = body != null && body.approver() != null ? body.approver() : "ui-user";
        String confirmation = body != null ? body.confirmation() : null;
        return ApiResponse.ok(agentService.approveProposal(id, proposalId, approver, confirmation,
                Locales.uiLocale(exchange.getRequest().getHeaders())));
    }

    /** 修复提案驳回（T023）：提案 REJECTED + 事故转 NEEDS_HUMAN。 */
    @PostMapping("/{id}/proposals/{proposalId}/reject")
    public ApiResponse<ApprovalService.ApprovalResult> rejectProposal(@PathVariable UUID id, @PathVariable UUID proposalId,
                                                                       @RequestBody(required = false) ProposalDecisionRequest body,
                                                                       @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        queryService.detail(TenantContext.tenantId(), pid, id);
        projectAuthz.require("project:manage", pid);
        String approver = body != null && body.approver() != null ? body.approver() : "ui-user";
        return ApiResponse.ok(agentService.rejectProposal(id, proposalId, approver));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProposalDecisionRequest(String approver, String confirmation) {
    }

    /** T026：人工标记已处理（NEEDS_HUMAN 限定）→ 转 ACTING 触发复验。 */
    @PostMapping("/{id}/mark-handled")
    public ApiResponse<Void> markHandled(@PathVariable UUID id, @RequestBody(required = false) NoteRequest body,
                                          @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        queryService.detail(TenantContext.tenantId(), pid, id);
        agentService.markHandled(id, body != null ? body.note() : null, body != null ? body.actor() : null);
        return ApiResponse.ok(null);
    }

    /** T026：显式触发复验（NEEDS_HUMAN 限定），经闸门 incident_reverify。 */
    @PostMapping("/{id}/reverify")
    public ApiResponse<Void> reverify(@PathVariable UUID id, @RequestBody(required = false) NoteRequest body,
                                       @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        queryService.detail(TenantContext.tenantId(), pid, id);
        agentService.reverify(id, body != null ? body.actor() : null);
        return ApiResponse.ok(null);
    }

    /** T026：人工直接收口（任意非终态 → RESOLVED(MANUAL)），reason 必填。 */
    @PostMapping("/{id}/close")
    public ApiResponse<Void> close(@PathVariable UUID id, @RequestBody(required = false) CloseRequest body,
                                    @RequestParam(required = false) Long projectId) {
        long pid = resolveProjectId(projectId);
        queryService.detail(TenantContext.tenantId(), pid, id);
        agentService.closeManual(id, body != null ? body.reason() : null, body != null ? body.actor() : null);
        return ApiResponse.ok(null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NoteRequest(String note, String actor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CloseRequest(String reason, String actor) {
    }

    /** 智能运维启停开关（FR-012，租户级，独立于 053 血缘富化 enabled）。 */
    @GetMapping("/agent-config")
    public ApiResponse<AgentConfigStatus> getAgentConfig() {
        projectRoleService.requireTenantAdmin(TenantContext.tenantId(), TenantContext.userId());
        boolean opsEnabled = agentConfigService.isOpsEnabledFor(TenantContext.tenantId());
        return ApiResponse.ok(new AgentConfigStatus(opsEnabled));
    }

    @PutMapping("/agent-config")
    public ApiResponse<AgentConfigStatus> putAgentConfig(@RequestBody AgentConfigStatus req) {
        projectRoleService.requireTenantAdmin(TenantContext.tenantId(), TenantContext.userId());
        agentConfigService.setOpsEnabled(TenantContext.tenantId(), req.opsEnabled());
        return ApiResponse.ok(new AgentConfigStatus(agentConfigService.isOpsEnabledFor(TenantContext.tenantId())));
    }

    public record AgentConfigStatus(boolean opsEnabled) {
    }

    private long resolveProjectId(Long requestProjectId) {
        Long pid = requestProjectId != null ? requestProjectId : TenantContext.projectId();
        return projectScope.require(TenantContext.tenantId(), TenantContext.userId(), pid);
    }
}
