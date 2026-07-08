package com.dataweave.master.application.lineage.agent;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.dataweave.master.domain.lineage.LineageAgentConfig;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 053 Anthropic Messages 协议适配（契约 llm-protocol-adapters C2）。
 * 端点 {base_url}/v1/messages；头 x-api-key + anthropic-version；结构化输出用 tools + tool_choice（强制调用 emit_lineage）。
 */
@Component
public class AnthropicProtocolAdapter implements LlmProtocolAdapter {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /**
     * 输出 token 上限（Anthropic Messages API 要求 max_tokens 必填）。
     * 原值 1024 对 reasoning 模型（如经 Anthropic 兼容端点接入的 qwen3-max 等）会截断——其思维链先吃掉数百 token，
     * {@code emit_lineage} tool_call 未及产出即被截，导致 {@link #parseResponse} 拿到空 content 静默降级为空产物。
     * 提到 8192 给「思维链 + 结构化输出」充足余量；emit_lineage 产物本身很小，模型完成即停，慷慨上限不带来额外成本
     * （单次外呼总时长仍由 {@code cfg.timeoutMs} 兜底）。对应 da88955 在评测侧（ml/lineage-extractor）已吸取的同一教训。
     */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String protocol() {
        return "ANTHROPIC";
    }

    @Override
    public HttpRequest buildRequest(LineageAgentConfig cfg, LineageExtractionPrompt.LineagePrompt prompt, String apiKeyPlain) {
        String url = LlmProtocolAdapter.stripTrailingSlash(cfg.baseUrl()) + "/v1/messages";
        Map<String, Object> body = Map.of(
                "model", cfg.model(),
                "max_tokens", MAX_OUTPUT_TOKENS,
                "system", prompt.system(),
                "messages", List.of(Map.of("role", "user", "content", prompt.user())),
                "tools", List.of(Map.of(
                        "name", "emit_lineage",
                        "description", "Emit extracted table-level reads/writes and column-level derivations.",
                        "input_schema", EMISSION_SCHEMA)),
                "tool_choice", Map.of("type", "tool", "name", "emit_lineage"));
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(500, cfg.timeoutMs())))
                    .header("content-type", "application/json")
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (apiKeyPlain != null && !apiKeyPlain.isEmpty()) {
                b.header("x-api-key", apiKeyPlain);
            }
            return b.build();
        } catch (Exception e) {
            throw new IllegalStateException("anthropic request build failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public AgentExtraction parseResponse(String body, String modelVersion) {
        try {
            Map<String, Object> root = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            Object contentRaw = root.get("content");
            if (contentRaw instanceof List<?> list) {
                for (Object block : list) {
                    if (block instanceof Map<?, ?> m
                            && "tool_use".equals(m.get("type"))
                            && "emit_lineage".equals(m.get("name"))
                            && m.get("input") instanceof Map<?, ?> im) {
                        return LlmProtocolAdapter.mapExtraction((Map<String, Object>) im, modelVersion);
                    }
                }
            }
            return AgentExtraction.empty(modelVersion);
        } catch (Exception e) {
            throw new IllegalStateException("anthropic response parse failed", e);
        }
    }
}
