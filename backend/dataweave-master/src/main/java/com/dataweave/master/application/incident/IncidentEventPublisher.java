package com.dataweave.master.application.incident;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.incident.IncidentEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 指挥中心直播流事件发布器（069）：把 {@link IncidentEvent} 序列化后发到项目域频道
 * {@code dw:incident:evt:{projectId}}，供 SSE 端桥接（契约：sse-live-feed.md）。
 * EventBus 全丢不影响正确性（事件是观测层，非状态推进本身）；序列化异常吞掉不拖垮主链路。
 */
@Component
public class IncidentEventPublisher {

    private final EventBus eventBus;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IncidentEventPublisher(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void publish(long projectId, IncidentEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(new Envelope(event.eventName(), event));
            eventBus.publish(channel(projectId), payload);
        } catch (Exception e) {
            // 直播是观测层，序列化/总线异常绝不拖垮主链路（同 053 LlmAgentClient 降级惯例）
        }
    }

    public static String channel(long projectId) {
        return "dw:incident:evt:" + projectId;
    }

    /** SSE 桥接端按 {@code event} 分派 event-name，{@code data} 即 record 本体。 */
    public record Envelope(String event, Object data) {
    }
}
