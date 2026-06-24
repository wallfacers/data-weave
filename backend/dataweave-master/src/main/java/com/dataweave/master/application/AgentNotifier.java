package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.Finding;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 主动播报出口：把主动发现/主动开口经 {@link EventBus} 广播到统一频道 {@link #CHANNEL}，
 * 由 api 侧持久 SSE 端点 {@code GET /api/agent/stream} 订阅后 fan-out 给所有客户端。
 *
 * <p>沿用「DB 保正确性、总线保实时性」：总线全丢时举手台仍可经 {@code GET /api/findings} 拉取自愈，
 * 故发布失败不抛、不影响主流程。all-in-one 走 InMemoryEventBus 进程内直达，distributed 走 Redis pub/sub。
 *
 * <p>消息信封：{@code {"event":"agent.finding"|"agent.message","data":{…}}}。
 */
@Component
public class AgentNotifier {

    /** 统一主动播报频道。 */
    public static final String CHANNEL = "dw:agent:notify";

    private final EventBus eventBus;
    private final ObjectMapper objectMapper;

    public AgentNotifier(EventBus eventBus, ObjectMapper objectMapper) {
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
    }

    /** 推送一条新发现（举手台据此实时刷新）。 */
    public void finding(Finding f) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", f.getId());
        data.put("source", f.getSource());
        data.put("severity", f.getSeverity());
        data.put("targetType", f.getTargetType());
        data.put("targetId", f.getTargetId());
        data.put("title", f.getTitle());
        data.put("rootCause", f.getRootCause());
        data.put("evidenceJson", f.getEvidenceJson());
        data.put("actionsJson", f.getActionsJson());
        data.put("status", f.getStatus());
        publish("agent.finding", data);
    }

    /** 推送一条 Agent 主动开口消息（左栏聊天台据此在无人发问时追加助手消息）。 */
    public void message(String sessionId, String markdown, Long findingId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("markdown", markdown);
        data.put("findingId", findingId);
        publish("agent.message", data);
    }

    private void publish(String event, Object data) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("event", event);
            envelope.put("data", data);
            eventBus.publish(CHANNEL, objectMapper.writeValueAsString(envelope));
        } catch (RuntimeException ignored) {
            // 播报仅作实时性，失败不影响主流程（举手台可拉取自愈）。
        }
    }
}
