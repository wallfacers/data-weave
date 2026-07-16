package com.dataweave.api.interfaces.companion;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ProjectScope;
import com.dataweave.master.companion.application.CompanionBriefingService;
import com.dataweave.master.companion.application.CompanionEventPublisher;
import com.dataweave.master.companion.application.CompanionStateResolver;
import com.dataweave.master.companion.domain.Briefing;
import com.dataweave.master.companion.domain.MessageView;
import com.dataweave.master.companion.domain.ReportView;
import com.dataweave.master.companion.infrastructure.JdbcCompanionMessageRepository;
import com.dataweave.master.companion.infrastructure.JdbcPatrolReportRepository;
import com.dataweave.master.domain.EventBus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 管家 SSE 直播流（{@code GET /api/companion/stream?projectId&token}）。镜像 incident stream 骨架：
 * 建连发 snapshot（形态+概况+未关闭汇报）→ 可选 Last-Event-ID 补齐落库消息 → 桥接项目级频道
 * {@code dw:companion:evt:{projectId}} 实时事件（state/report/briefing/message/delta/end）+ 心跳保活。
 *
 * <p>鉴权经 query {@code token}+{@code projectId}（{@code JwtAuthFilter} 已处理，EventSource 无自定义 header；
 * 失败裸 401）。{@code SseNoBufferingWebFilter} 自动加防缓冲头，SSE 帧直连 {@code SSE_BASE} 绕 Next rewrite。
 */
@RestController
@RequestMapping("/api/companion")
public class CompanionStreamHandler {

    private static final int SNAPSHOT_REPORT_LIMIT = 200;
    private static final int REPLAY_LIMIT = 500;
    private static final Duration HEARTBEAT = Duration.ofSeconds(15);

    private final EventBus eventBus;
    private final JdbcPatrolReportRepository reportRepo;
    private final JdbcCompanionMessageRepository messageRepo;
    private final CompanionStateResolver stateResolver;
    private final CompanionBriefingService briefingService;
    private final ProjectScope projectScope;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CompanionStreamHandler(EventBus eventBus, JdbcPatrolReportRepository reportRepo,
                                  JdbcCompanionMessageRepository messageRepo, CompanionStateResolver stateResolver,
                                  CompanionBriefingService briefingService, ProjectScope projectScope) {
        this.eventBus = eventBus;
        this.reportRepo = reportRepo;
        this.messageRepo = messageRepo;
        this.stateResolver = stateResolver;
        this.briefingService = briefingService;
        this.projectScope = projectScope;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam(required = false) Long projectId,
                                                @RequestParam(name = "lastEventId", required = false) String lastEventIdParam,
                                                ServerWebExchange exchange) {
        long tenantId = TenantContext.tenantId();
        long pid = resolveProjectId(projectId);
        String lastEventId = lastEventIdParam != null ? lastEventIdParam
                : exchange.getRequest().getHeaders().getFirst("Last-Event-ID");

        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();
        EventBus.Subscription subscription = eventBus.subscribe(CompanionEventPublisher.channel(pid),
                message -> sink.tryEmitNext(toSse(message)));

        Flux<ServerSentEvent<String>> snapshot = Flux.defer(() -> Flux.fromIterable(snapshotEvents(tenantId, pid)));
        Flux<ServerSentEvent<String>> replay = Flux.defer(() -> Flux.fromIterable(replayEvents(tenantId, pid, lastEventId)));
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(HEARTBEAT)
                .map(t -> ServerSentEvent.<String>builder().comment("ping").build());

        // snapshot → replay（补齐落库消息）→ 实时事件 + 心跳保活（反代不掐长连接）
        return Flux.concat(snapshot, replay, Flux.merge(sink.asFlux(), heartbeat))
                .doFinally(s -> {
                    try {
                        subscription.close();
                    } catch (Exception e) {
                        // 退订失败忽略
                    }
                });
    }

    /** 连接即全量：形态 + 概况 + 未关闭汇报（时间倒序）。 */
    private List<ServerSentEvent<String>> snapshotEvents(long tenantId, long pid) {
        try {
            String state = stateResolver.resolve(tenantId, pid);
            Briefing briefing = briefingService.compute(tenantId, pid);
            List<ReportView> reports = reportRepo.findOpenByProject(tenantId, pid, SNAPSHOT_REPORT_LIMIT).stream()
                    .map(ReportView::from).toList();
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("state", state);
            snap.put("briefing", briefing);
            snap.put("reports", reports);
            String data = objectMapper.writeValueAsString(snap);
            return List.of(ServerSentEvent.<String>builder().event("snapshot").data(data).build());
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Last-Event-ID 续传：补齐离线期间落库的会话消息（瞬态 delta/end 不重放）。 */
    private List<ServerSentEvent<String>> replayEvents(long tenantId, long pid, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) return List.of();
        long afterId;
        try {
            afterId = Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return List.of();
        }
        return messageRepo.findAfterId(tenantId, pid, afterId, REPLAY_LIMIT).stream()
                .map(m -> {
                    MessageView mv = MessageView.from(m);
                    try {
                        return ServerSentEvent.<String>builder().event("message")
                                .id(String.valueOf(mv.id())).data(objectMapper.writeValueAsString(mv)).build();
                    } catch (Exception e) {
                        return ServerSentEvent.<String>builder().event("message").data("{}").build();
                    }
                })
                .toList();
    }

    /** EventBus Envelope JSON → SSE 帧（Envelope.event → event:，Envelope.data → data:）。 */
    private ServerSentEvent<String> toSse(String envelopeJson) {
        try {
            JsonNode node = objectMapper.readTree(envelopeJson);
            String event = node.hasNonNull("event") ? node.get("event").asString() : "message";
            JsonNode dataNode = node.has("data") ? node.get("data") : null;
            String data = dataNode != null ? dataNode.toString() : "{}";
            var builder = ServerSentEvent.<String>builder().event(event).data(data);
            // M6：message 事件携带 SSE id（MessageView.id）——EventSource 原生 Last-Event-ID 续传才生效
            // （replay 路径已设 id；此前实时 message 帧漏设，断线重连补不齐离线期间落库的会话消息）
            if ("message".equals(event) && dataNode != null && dataNode.hasNonNull("id")) {
                builder.id(dataNode.get("id").asString());
            }
            return builder.build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder().event("message").data(envelopeJson).build();
        }
    }

    private long resolveProjectId(Long requestProjectId) {
        Long pid = requestProjectId != null ? requestProjectId : TenantContext.projectId();
        return projectScope.require(TenantContext.tenantId(), TenantContext.userId(), pid);
    }
}
