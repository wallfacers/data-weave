package com.dataweave.api.application.bridge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * workhorse-agent 客户端生产实现，对齐真实 headless API（support-dataweave-headless-integration）。
 *
 * <p>API 模型为 <b>解耦 pub/sub</b>：
 * <ul>
 *   <li>{@code POST /v1/sessions} 建会话（{instructions, metadata}），返回 {@code {id, status, ...}}。</li>
 *   <li>一轮对话 = {@code POST /v1/sessions/{id}/stream}（{@code {type:"user_message", content}}）提交（202），
 *       再 {@code GET /v1/sessions/{id}/stream} 带 {@code Last-Event-ID} 订阅 SSE。</li>
 * </ul>
 *
 * <p>SSE 帧形如 {@code id: <idx>\nevent: <type>\ndata: <json>\n\n} —— 事件类型在 {@code event:} 段，
 * 不在 data JSON 里。turn 终结信号为 {@code assistant_text_done} 且 {@code stop_reason != "tool_use"}
 * （或 {@code error}/{@code interrupted}），据此 {@code takeUntil} 让 Flux 完成、关闭 GET 连接。
 *
 * <p>事件字段映射到 {@link WorkhorseEvent}（桥层消费的内部契约）：
 * {@code assistant_text_delta.delta→text}、{@code tool_call_start{id,tool,input}}、
 * {@code tool_call_done{id,output,ok}}、{@code permission_resolved{request_id,decision,source}}。
 */
@Component
public class WorkhorseHttpClient implements WorkhorseClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    /** 每个 workhorse 会话的已消费事件 idx 游标，用于 Last-Event-ID 增量续传（跨轮不重放旧事件）。 */
    private final Map<String, Long> lastEventIdBySession = new ConcurrentHashMap<>();

    public WorkhorseHttpClient(WebClient.Builder builder,
                               ObjectMapper objectMapper,
                               @Value("${agent.workhorse.base-url:http://127.0.0.1:8300}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String createSession(String instructions, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instructions", instructions);
        body.put("metadata", stringifyValues(metadata));
        Map<String, Object> resp = webClient.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
        Object id = resp == null ? null : resp.get("id");
        return id == null ? null : id.toString();
    }

    @Override
    public Flux<WorkhorseEvent> sendMessage(String sessionId, String content) {
        Mono<Void> submit = webClient.post().uri("/v1/sessions/{id}/stream", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "user_message", "content", content))
                .retrieve()
                .bodyToMono(Void.class);

        Flux<WorkhorseEvent> stream = webClient.get().uri("/v1/sessions/{id}/stream", sessionId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Last-Event-ID",
                        Long.toString(lastEventIdBySession.getOrDefault(sessionId, 0L)))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .doOnNext(sse -> rememberIdx(sessionId, sse))
                .mapNotNull(this::parse)
                .takeUntil(ev -> "done".equals(ev.type()))
                .filter(ev -> !"done".equals(ev.type()));

        return submit.thenMany(stream);
    }

    /**
     * workhorse {@code POST /v1/sessions} 要求 metadata 为<b>字符串字典</b>：非字符串值
     * （boolean/number/null）会被拒 400。在客户端边界统一把值 coerce 成字符串，避免每个调用点
     * 各自记得传字符串而 regress（曾因 {@code {"headless": true}} 致诊断会话建立失败回落 mock）。
     */
    static Map<String, Object> stringifyValues(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        metadata.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        return out;
    }

    private void rememberIdx(String sessionId, ServerSentEvent<String> sse) {
        String id = sse.id();
        if (id == null) {
            return;
        }
        try {
            lastEventIdBySession.put(sessionId, Long.parseLong(id.trim()));
        } catch (NumberFormatException ignored) {
            // keepalive/comment frames have no id; ignore
        }
    }

    /** 把一条 workhorse SSE 事件映射成内部 {@link WorkhorseEvent}；无关事件返回 null（跳过）。 */
    private WorkhorseEvent parse(ServerSentEvent<String> sse) {
        String type = sse.event();
        if (type == null) {
            return null;
        }
        Map<String, Object> d = dataOf(sse.data());
        return switch (type) {
            case "assistant_text_delta" -> {
                String delta = str(d, "delta");
                yield delta == null ? null : WorkhorseEvent.text(delta);
            }
            case "tool_call_start" -> WorkhorseEvent.toolStart(
                    str(d, "id"), str(d, "tool"), jsonOf(d.get("input")));
            case "tool_call_done" -> {
                String output = str(d, "output");
                boolean truncated = output != null && output.contains("[truncated:");
                yield WorkhorseEvent.toolDone(str(d, "id"), output, truncated);
            }
            case "permission_resolved" -> WorkhorseEvent.permission(
                    str(d, "request_id"), str(d, "decision"), str(d, "source"));
            case "assistant_text_done" -> {
                String stop = str(d, "stop_reason");
                // 非 tool_use 即本轮终结；缺失 stop_reason 时保守判定为终结，避免挂死。
                yield (stop == null || !stop.equals("tool_use")) ? WorkhorseEvent.done() : null;
            }
            case "error", "interrupted" -> WorkhorseEvent.done();
            default -> null;     // reasoning_*/compaction/provider_retry/pong/... 不进 AG-UI 文本流
        };
    }

    private Map<String, Object> dataOf(String data) {
        if (data == null || data.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String jsonOf(Object o) {
        return o == null ? null : objectMapper.writeValueAsString(o);
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
