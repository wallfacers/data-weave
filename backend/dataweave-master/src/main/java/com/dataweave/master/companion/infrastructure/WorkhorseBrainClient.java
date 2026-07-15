package com.dataweave.master.companion.infrastructure;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.dataweave.master.companion.domain.ChatCallbacks;
import com.dataweave.master.companion.domain.ChatHandle;
import com.dataweave.master.companion.domain.CompanionBrain;
import com.dataweave.master.companion.domain.PatrolResult;
import com.dataweave.master.companion.domain.PatrolRoutine;
import com.dataweave.master.companion.domain.ReportSeverities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * workhorse sidecar 大脑适配器（HTTP 8300）。推理在 sidecar 进程，本类只做编排/通道——
 * 满足 constitution IV"服务端无 AI 大脑"。token 不出后端（前端永不直连 workhorse）。
 *
 * <p>workhorse 协议（{@code support-dataweave-headless-integration}）：
 * <ul>
 *   <li>{@code POST /v1/sessions} {agent_type:"companion", instructions, ephemeral} → {id}</li>
 *   <li>双向流：{@code GET /v1/sessions/{id}/stream}（SSE 订阅）+ {@code POST /v1/sessions/{id}/stream}（发消息，返 202）</li>
 *   <li>{@code POST /v1/sessions/{id}/cancel}（打断）</li>
 *   <li>SSE 事件：{@code assistant_text_delta}（增量）/ {@code assistant_text_done}（turn 终结，每轮一次）/ {@code interrupted} / {@code error}</li>
 * </ul>
 *
 * <p>降级：{@link #healthy} 探测失败 → 由 {@code CompanionBrainSelector} 切 {@link MockBrain}（巡检）或
 * 拒绝（对话 → {@code companion.brain_unavailable}）。runPatrol 任何异常/超时 → {@link PatrolResult#failed}
 * 交 {@code PatrolService} 兜底"未完成"汇报（SC-007）。
 */
@Component
public class WorkhorseBrainClient implements CompanionBrain {

    private static final Logger log = LoggerFactory.getLogger(WorkhorseBrainClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String baseUrl;
    private final String agentType;
    private final int chatTurnTimeoutSeconds;

    public WorkhorseBrainClient(@Value("${companion.brain.base-url:http://127.0.0.1:8300}") String baseUrl,
                                @Value("${companion.brain.agent-type:companion}") String agentType,
                                @Value("${companion.brain.chat-turn-timeout-seconds:120}") int chatTurnTimeoutSeconds) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.agentType = agentType;
        this.chatTurnTimeoutSeconds = chatTurnTimeoutSeconds;
    }

    @Override
    public String name() {
        return "workhorse";
    }

    @Override
    public boolean healthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public PatrolResult runPatrol(PatrolRoutine routine, String scopeJson, int timeoutSeconds) {
        if (!healthy()) {
            return PatrolResult.failed("workhorse 大脑不可用");
        }
        String prompt = buildPatrolPrompt(routine, scopeJson);
        String sessionId = null;
        try {
            sessionId = createSession(prompt, true);
            String text = streamTurn(sessionId, "请立即执行本轮巡检并只输出约定的 JSON 对象，不要任何额外文字。",
                    timeoutSeconds, null);
            return parsePatrolJson(text);
        } catch (Exception e) {
            log.warn("[CompanionBrain] runPatrol domain={} 失败: {}", routine.domain(), e.toString());
            return PatrolResult.failed("巡检失败: " + e.getMessage());
        } finally {
            if (sessionId != null) deleteSession(sessionId);
        }
    }

    @Override
    public ChatHandle openChat(long projectId, String contextPrompt, ChatCallbacks callbacks) {
        String sessionId = createSession(contextPrompt, false);
        return new WorkhorseChatHandle(sessionId, callbacks);
    }

    // ===== HTTP 原语 =====

    private String createSession(String instructions, boolean ephemeral) {
        try {
            String body = MAPPER.writeValueAsString(new java.util.LinkedHashMap<>() {{
                    put("agent_type", agentType);
                    put("instructions", instructions == null ? "" : instructions);
                    put("ephemeral", ephemeral);
            }});
            HttpRequest req = jsonRequest("POST", "/v1/sessions").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("createSession http " + resp.statusCode() + ": " + body(resp));
            }
            JsonNode node = MAPPER.readTree(resp.body());
            String id = node.path("id").asString(null);
            if (id == null || id.isBlank()) throw new IllegalStateException("createSession 无 id: " + body(resp));
            return id;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("createSession 失败: " + e.getMessage(), e);
        }
    }

    private void postMessage(String sessionId, String text) throws Exception {
        String body = MAPPER.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("type", "user_message");
            put("content", text);
        }});
        HttpRequest req = jsonRequest("POST", "/v1/sessions/" + sessionId + "/stream")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() != 202 && (resp.statusCode() < 200 || resp.statusCode() >= 300)) {
            throw new IllegalStateException("postMessage http " + resp.statusCode());
        }
    }

    private void postCancel(String sessionId) {
        try {
            HttpRequest req = jsonRequest("POST", "/v1/sessions/" + sessionId + "/cancel")
                    .POST(HttpRequest.BodyPublishers.ofString("")).build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("[CompanionBrain] cancel session={} 未生效（可忽略）: {}", sessionId, e.toString());
        }
    }

    private void deleteSession(String sessionId) {
        try {
            HttpRequest req = jsonRequest("DELETE", "/v1/sessions/" + sessionId).build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("[CompanionBrain] delete session={} 失败（可忽略）: {}", sessionId, e.toString());
        }
    }

    private HttpRequest.Builder jsonRequest(String method, String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody());
    }

    private static String body(HttpResponse<String> resp) {
        return resp.body() == null ? "" : resp.body().length() > 500 ? resp.body().substring(0, 500) : resp.body();
    }

    /**
     * 消费单轮 SSE：开 GET 订阅 → POST 消息 → 逐行读取，累积 assistant_text_delta 直至
     * {@code assistant_text_done}（turn 终结）/ interrupted / error / 超时。
     *
     * @param onDelta 可空；非空时每个增量片段回调（对话直播用）。返回本轮完整文本。
     */
    String streamTurn(String sessionId, String message, int timeoutSeconds, java.util.function.Consumer<String> onDelta)
            throws Exception {
        // 1. 开 GET 订阅（ofInputStream：headers 到达即返回，body 惰性流式）
        HttpRequest getReq = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/sessions/" + sessionId + "/stream"))
                .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .header("Accept", "text/event-stream").GET().build();
        HttpResponse<java.io.InputStream> resp = http.send(getReq, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("stream http " + resp.statusCode());
        }
        // 2. POST 触发本轮（workhorse 在 GET 流上推送事件）
        postMessage(sessionId, message);
        // 3. 读 SSE
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            String event = null;
            StringBuilder data = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                } else if (line.isEmpty()) {
                    String ev = event;
                    if (ev != null) {
                        String ret = handleEvent(ev, data.toString(), text, onDelta);
                        if (ret != null) return ret;
                    }
                    event = null;
                    data.setLength(0);
                }
            }
        }
        return text.toString();
    }

    /** 处理一个 SSE 事件边界。返回非 null = 终止本轮（值=完整文本）；null = 继续。 */
    private static String handleEvent(String event, String data, StringBuilder text,
                                      java.util.function.Consumer<String> onDelta) {
        return switch (event) {
            case "assistant_text_delta" -> {
                String delta = extractText(data);
                if (delta != null && !delta.isEmpty()) {
                    text.append(delta);
                    if (onDelta != null) onDelta.accept(delta);
                }
                yield null;
            }
            case "assistant_text_done" -> text.toString();   // turn 终结
            case "interrupted" -> text.toString();            // 被打断，半截文本
            default -> null;                                  // tool/permission/reasoning 等忽略
        };
    }

    /** 从 assistant_text_delta 的 data JSON 取 text 字段；解析失败返回 null。 */
    static String extractText(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(dataJson);
            if (node.hasNonNull("text")) return node.path("text").asString();
            if (node.hasNonNull("delta")) return node.path("delta").asString();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 brain 产出的 JSON 巡检汇报。容错：剥 markdown 围栏 / 截取首个 {...}；任一字段缺失或非法 → failed。
     */
    static PatrolResult parsePatrolJson(String raw) {
        if (raw == null || raw.isBlank()) return PatrolResult.failed("brain 返回空文本");
        String json = stripToJson(raw);
        if (json == null) return PatrolResult.failed("brain 返回非 JSON: " + truncate(raw));
        try {
            JsonNode node = MAPPER.readTree(json);
            String severity = normSeverity(node.path("severity").asString(null));
            String title = node.path("title").asString(null);
            String summary = node.path("summary").asString(null);
            JsonNode detail = node.path("detail");
            if (severity == null || title == null || title.isBlank()) {
                return PatrolResult.failed("JSON 缺 severity/title: " + truncate(raw));
            }
            String detailJson = detail.isMissingNode() || detail.isNull() ? "{}" : MAPPER.writeValueAsString(detail);
            return PatrolResult.ok(severity, title, summary == null ? title : summary, detailJson);
        } catch (Exception e) {
            return PatrolResult.failed("JSON 解析失败: " + e.getMessage());
        }
    }

    /** 截取首个完整 JSON 对象（剥 ```json 围栏与前后叙述）。 */
    static String stripToJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }

    private static String normSeverity(String s) {
        if (s == null) return null;
        String up = s.trim().toUpperCase();
        return ReportSeverities.DANGER.equals(up) || ReportSeverities.WARN.equals(up)
                || ReportSeverities.OK.equals(up) || ReportSeverities.INFO.equals(up) ? up : null;
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) : s);
    }

    private static String buildPatrolPrompt(PatrolRoutine routine, String scopeJson) {
        return "【巡检模式·" + routine.domain() + "】请用 dataweave__ 只读工具扫描本项目(project_id 见会话元数据)的"
                + domainHint(routine.domain()) + "。范围参数: " + (scopeJson == null ? "全项目" : scopeJson)
                + "。严格基于真实数据判断,查不到就报无异常,严禁臆测。完成后只输出一个 JSON 对象:"
                + "{\"severity\":\"DANGER|WARN|OK|INFO\",\"title\":\"≤40字\",\"summary\":\"≤80字播报\","
                + "\"detail\":{\"objects\":[{\"type\":\"TASK|NODE|TABLE|SCRIPT\",\"id\":\"\",\"name\":\"\"}],"
                + "\"aggregateCount\":1,\"suggestions\":[]}}。一切正常回 OK。";
    }

    private static String domainHint(String domain) {
        return switch (domain) {
            case "TASK_FAILURE" -> "近期失败/挂起任务实例与失败原因聚类";
            case "MACHINE" -> "节点在线/离线、心跳与并发负载";
            case "DATA_QUALITY" -> "数据新鲜度、SLA 破线、空表/异常波动";
            case "CODE_QUALITY" -> "任务脚本/调度配置的近期可疑变更";
            default -> "相关异常";
        };
    }

    /** 会话句柄：send 阻塞至本轮结束（回调 delta/end），cancel 跨线程打断。 */
    private class WorkhorseChatHandle implements ChatHandle {
        private final String sessionId;
        private final ChatCallbacks callbacks;
        private final AtomicReference<String> currentMessage = new AtomicReference<>();

        WorkhorseChatHandle(String sessionId, ChatCallbacks callbacks) {
            this.sessionId = sessionId;
            this.callbacks = callbacks;
        }

        @Override public String sessionId() { return sessionId; }

        @Override
        public String send(String userText) {
            currentMessage.set(userText);
            try {
                String full = streamTurn(sessionId, userText, chatTurnTimeoutSeconds, callbacks::onDelta);
                callbacks.onEnd(full, false);
                return full;
            } catch (Exception e) {
                callbacks.onError(e);
                return "";
            } finally {
                currentMessage.set(null);
            }
        }

        @Override
        public void cancel() {
            postCancel(sessionId);   // workhorse 收到 cancel → 流上发 interrupted → send 随即返回半截
        }
    }
}
