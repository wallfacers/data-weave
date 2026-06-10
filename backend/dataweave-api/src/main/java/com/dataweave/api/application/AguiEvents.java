package com.dataweave.api.application;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AG-UI 事件工厂：两模式（mock / workhorse）共用同一出口，保证事件 type（SCREAMING_SNAKE_CASE）
 * 与序列契约一致。data 为一段 JSON。
 */
@Component
public class AguiEvents {

    private final ObjectMapper objectMapper;

    public AguiEvents(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ServerSentEvent<String> runStarted(String threadId, String runId) {
        return sse(map("type", "RUN_STARTED", "threadId", threadId, "runId", runId));
    }

    public ServerSentEvent<String> textMessageStart(String messageId) {
        return sse(map("type", "TEXT_MESSAGE_START", "messageId", messageId, "role", "assistant"));
    }

    public ServerSentEvent<String> textMessageContent(String messageId, String delta) {
        return sse(map("type", "TEXT_MESSAGE_CONTENT", "messageId", messageId, "delta", delta));
    }

    public ServerSentEvent<String> textMessageEnd(String messageId) {
        return sse(map("type", "TEXT_MESSAGE_END", "messageId", messageId));
    }

    public ServerSentEvent<String> custom(String name, Object value) {
        return sse(map("type", "CUSTOM", "name", name, "value", value));
    }

    public ServerSentEvent<String> runFinished(String threadId, String runId) {
        Map<String, Object> finished = map("type", "RUN_FINISHED", "threadId", threadId, "runId", runId);
        finished.put("outcome", map("type", "success"));
        return sse(finished);
    }

    public ServerSentEvent<String> sse(Map<String, Object> payload) {
        return ServerSentEvent.<String>builder().data(objectMapper.writeValueAsString(payload)).build();
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
