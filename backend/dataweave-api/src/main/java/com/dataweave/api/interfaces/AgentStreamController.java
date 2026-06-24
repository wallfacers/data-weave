package com.dataweave.api.interfaces;

import com.dataweave.master.application.AgentNotifier;
import com.dataweave.master.domain.EventBus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Agent 主动播报持久 SSE 通道（agent-notification-stream）。
 *
 * <p>订阅 {@link AgentNotifier#CHANNEL}，把主动发现/主动开口实时 fan-out 给所有已连接客户端：
 * <ul>
 *   <li>{@code agent.finding} —— 新发现，举手台据此刷新；</li>
 *   <li>{@code agent.message} —— Agent 主动开口，左栏聊天台据此在无人发问时追加助手消息；</li>
 *   <li>{@code keepalive} —— 周期保活，防代理/客户端断连。</li>
 * </ul>
 * 事件名小写点分，与 AG-UI 的 SCREAMING_SNAKE_CASE 区分。{@code /agui} 契约不变。
 */
@RestController
public class AgentStreamController {

    private final EventBus eventBus;
    private final ObjectMapper objectMapper;

    public AgentStreamController(EventBus eventBus, ObjectMapper objectMapper) {
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/api/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream() {
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();

        var subscription = eventBus.subscribe(AgentNotifier.CHANNEL, message -> {
            String event = "agent.message";
            String data = message;
            try {
                JsonNode env = objectMapper.readTree(message);
                JsonNode ev = env.get("event");
                if (ev != null && !ev.isNull()) {
                    event = ev.asString();
                }
                JsonNode d = env.get("data");
                if (d != null && !d.isNull()) {
                    data = objectMapper.writeValueAsString(d);
                }
            } catch (RuntimeException ignored) {
                // 非信封格式 → 原样作为 agent.message 透传
            }
            sink.tryEmitNext(ServerSentEvent.<String>builder().event(event).data(data).build());
        });

        // 周期保活；与发现事件合流。客户端取消时关闭订阅。
        Flux<ServerSentEvent<String>> keepalive = Flux.interval(Duration.ofSeconds(20))
                .map(i -> ServerSentEvent.<String>builder().event("keepalive").data("{}").build());

        return Flux.merge(sink.asFlux(), keepalive)
                .doOnCancel(() -> {
                    try {
                        subscription.close();
                    } catch (Exception ignored) {
                        // 忽略
                    }
                });
    }
}
