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
 * 053 OpenAI Chat Completions 协议适配（契约 llm-protocol-adapters C3）。
 * 端点 {base_url}/v1/chat/completions；头 Authorization: Bearer；结构化输出用 response_format: json_schema。
 */
@Component
public class OpenAiProtocolAdapter implements LlmProtocolAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String protocol() {
        return "OPENAI";
    }

    @Override
    public HttpRequest buildRequest(LineageAgentConfig cfg, LineageExtractionPrompt.LineagePrompt prompt, String apiKeyPlain) {
        String url = LlmProtocolAdapter.stripTrailingSlash(cfg.baseUrl()) + "/v1/chat/completions";
        Map<String, Object> body = Map.of(
                "model", cfg.model(),
                "messages", List.of(
                        Map.of("role", "system", "content", prompt.system()),
                        Map.of("role", "user", "content", prompt.user())),
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of(
                                "name", "lineage_extraction",
                                "schema", EMISSION_SCHEMA,
                                "strict", true)));
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(500, cfg.timeoutMs())))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (apiKeyPlain != null && !apiKeyPlain.isEmpty()) {
                b.header("Authorization", "Bearer " + apiKeyPlain);
            }
            return b.build();
        } catch (Exception e) {
            throw new IllegalStateException("openai request build failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public AgentExtraction parseResponse(String body, String modelVersion) {
        try {
            Map<String, Object> root = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            Object choicesRaw = root.get("choices");
            if (choicesRaw instanceof List<?> choices && !choices.isEmpty()
                    && choices.get(0) instanceof Map<?, ?> first
                    && first.get("message") instanceof Map<?, ?> message
                    && message.get("content") instanceof String content && !content.isBlank()) {
                Map<String, Object> extraction = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
                return LlmProtocolAdapter.mapExtraction(extraction, modelVersion);
            }
            return AgentExtraction.empty(modelVersion);
        } catch (Exception e) {
            throw new IllegalStateException("openai response parse failed", e);
        }
    }
}
