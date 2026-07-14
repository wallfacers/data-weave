package com.dataweave.master.application.lineage.agent;

import java.io.BufferedReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.dataweave.master.application.DatasourceEncryptor;
import com.dataweave.master.domain.lineage.LineageAgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static java.util.stream.Collectors.toMap;

/**
 * 069 通用多轮对话客户端：诊断/对话/修复提案/战况播报复用同一 053 AI Agent 配置通道（协议/端点/模型/密钥），
 * 与 {@link LlmAgentClient}（血缘专用 emit_lineage 强制工具调用）并列，不改动其语义。
 * 同 053 三条硬约束：降级永不抛、apiKey 即用即弃不进日志、超时=cfg.timeoutMs。
 */
@Component
public class LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(LlmChatClient.class);

    private final Map<String, LlmProtocolAdapter> adapters;
    private final DatasourceEncryptor encryptor;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public LlmChatClient(List<LlmProtocolAdapter> adapterList, DatasourceEncryptor encryptor) {
        this.adapters = adapterList.stream().collect(toMap(LlmProtocolAdapter::protocol, a -> a));
        this.encryptor = encryptor;
    }

    /** 一条对话消息；role = "user" | "assistant"（system 另传，不混入 messages）。 */
    public record ChatMessage(String role, String content) {
    }

    /** 一次对话调用的归一结果；永不抛，error!=null 时调用方按降级处理（同 LlmAgentClient.CallResult 惯例）。 */
    public record ChatResult(String text, long latencyMs, String error) {
        public static ChatResult degraded(long latencyMs, String error) {
            return new ChatResult(null, latencyMs, error);
        }
    }

    /** 非流式对话：一次性拿到完整回复文本。 */
    public ChatResult chat(LineageAgentConfig cfg, String systemPrompt, List<ChatMessage> messages) {
        long t0 = System.nanoTime();
        LlmProtocolAdapter adapter = adapters.get(cfg.protocol());
        if (adapter == null) {
            return ChatResult.degraded(0, "no adapter for protocol: " + cfg.protocol());
        }
        try {
            String apiKeyPlain = decryptKey(cfg);
            HttpRequest req = adapter.buildChatRequest(cfg, systemPrompt, messages, apiKeyPlain, false);
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - t0) / 1_000_000;
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("[LlmChat] {} non-2xx ({}ms): status={}", cfg.protocol(), latency, resp.statusCode());
                return ChatResult.degraded(latency, "http " + resp.statusCode());
            }
            String text = adapter.parseChatText(resp.body());
            return new ChatResult(text, latency, null);
        } catch (Exception e) {
            long latency = (System.nanoTime() - t0) / 1_000_000;
            log.warn("[LlmChat] {} call degraded ({}ms): {}", cfg.protocol(), latency, e.toString());
            return ChatResult.degraded(latency, e.toString());
        }
    }

    /**
     * 流式对话：逐段回调 {@code onDelta}（直播 UX 用），返回值含拼接后的完整文本供落库。
     * SSE 逐行读取；仅识别 {@code data: } 前缀行，其余（event:/id:/心跳空行）忽略；
     * OpenAI 的 {@code data: [DONE]} 终止标记不视为增量。
     */
    public ChatResult streamChat(LineageAgentConfig cfg, String systemPrompt, List<ChatMessage> messages,
                                  Consumer<String> onDelta) {
        long t0 = System.nanoTime();
        LlmProtocolAdapter adapter = adapters.get(cfg.protocol());
        if (adapter == null) {
            return ChatResult.degraded(0, "no adapter for protocol: " + cfg.protocol());
        }
        StringBuilder full = new StringBuilder();
        try {
            String apiKeyPlain = decryptKey(cfg);
            HttpRequest req = adapter.buildChatRequest(cfg, systemPrompt, messages, apiKeyPlain, true);
            HttpResponse<java.io.InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            long latency = (System.nanoTime() - t0) / 1_000_000;
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("[LlmChat] {} stream non-2xx ({}ms): status={}", cfg.protocol(), latency, resp.statusCode());
                return ChatResult.degraded(latency, "http " + resp.statusCode());
            }
            try (BufferedReader reader = new BufferedReader(
                    new java.io.InputStreamReader(resp.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    String delta = adapter.parseChatDelta(data);
                    if (delta != null && !delta.isEmpty()) {
                        full.append(delta);
                        onDelta.accept(delta);
                    }
                }
            }
            long total = (System.nanoTime() - t0) / 1_000_000;
            return new ChatResult(full.toString(), total, null);
        } catch (Exception e) {
            long latency = (System.nanoTime() - t0) / 1_000_000;
            log.warn("[LlmChat] {} stream degraded ({}ms): {}", cfg.protocol(), latency, e.toString());
            // 已拼接的部分文本仍返回，调用方可决定是否采信半截回复
            return new ChatResult(full.length() > 0 ? full.toString() : null, latency, e.toString());
        }
    }

    private String decryptKey(LineageAgentConfig cfg) {
        if (cfg.apiKeyEnc() == null || cfg.apiKeyEnc().isEmpty()) return null;
        try {
            return encryptor.decrypt(cfg.apiKeyEnc());
        } catch (Exception e) {
            log.warn("[LlmChat] apiKey decrypt failed (treated as no key): {}", e.toString());
            return null;
        }
    }
}
