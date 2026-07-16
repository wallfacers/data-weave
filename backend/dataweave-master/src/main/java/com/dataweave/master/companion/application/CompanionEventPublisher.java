package com.dataweave.master.companion.application;

import com.dataweave.master.companion.domain.CompanionEvent;
import com.dataweave.master.domain.EventBus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * companion SSE 扇出 publisher（镜像 IncidentEventPublisher）。业务 service 发领域事件 →
 * 序列化为 {@code Envelope(event, data)} → 经 {@link EventBus} 发项目级频道 → SSE 桥接端转帧。
 *
 * <p>channel 按 project_id 分隔：{@code dw:companion:evt:{projectId}}（与 incident 频道隔离）。
 * 序列化/总线异常绝不拖垮主链路（直播是观测层，正确性由 DB 保，Redis 保实时性）。
 */
@Component
public class CompanionEventPublisher {

    private final EventBus eventBus;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CompanionEventPublisher(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void publish(long projectId, CompanionEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(new Envelope(event.eventName(), event.payload()));
            eventBus.publish(channel(projectId), payload);
        } catch (Exception e) {
            // 直播是观测层，序列化/总线异常绝不拖垮主链路（DB 保正确性，Redis 只保实时性）
        }
    }

    /** 项目级事件频道（SSE 桥接端订阅同一 channel）。 */
    public static String channel(long projectId) {
        return "dw:companion:evt:" + projectId;
    }

    /** SSE 桥接端按 event 分派 event-name，data 即事件本体。 */
    public record Envelope(String event, Object data) {}
}
