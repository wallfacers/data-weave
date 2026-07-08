package com.dataweave.master.application.lineage.agent;

import java.net.http.HttpRequest;
import java.util.List;

import com.dataweave.master.domain.lineage.LineageAgentConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 053 双协议适配器归一单测（T016，契约 llm-protocol-adapters FR-002）。
 * 两协议 parseResponse 后归一为等价 AgentExtraction；buildRequest 构造的 URL/鉴权头按协议差异正确。
 */
class ProtocolAdapterTest {

    private static final String MODEL = "test-model";

    private static LineageAgentConfig cfg(String protocol, String baseUrl) {
        return new LineageAgentConfig(null, 1L, 1L, protocol, baseUrl, MODEL, "enc", true,
                30000, 60, 2000, null, null, null, null, 0, 0);
    }

    // ---- parseResponse 归一（两协议语义等价，FR-002）----

    @Test
    void anthropicParsesToolUseInput() {
        AnthropicProtocolAdapter adapter = new AnthropicProtocolAdapter();
        String body = "{\"content\":[{\"type\":\"tool_use\",\"name\":\"emit_lineage\","
                + "\"input\":{\"reads\":[\"user\"],\"writes\":[\"dw.snap\"],"
                + "\"columnEdges\":[{\"srcTable\":\"user\",\"srcColumn\":\"id\","
                + "\"dstTable\":\"dw.snap\",\"dstColumn\":\"id\"}],\"confidence\":0.9}}]}";
        AgentExtraction ex = adapter.parseResponse(body, MODEL);
        assertThat(ex.reads()).containsExactly("user");
        assertThat(ex.writes()).containsExactly("dw.snap");
        assertThat(ex.columnEdges()).hasSize(1);
        assertThat(ex.columnEdges().get(0).srcTable()).isEqualTo("user");
        assertThat(ex.confidence()).isEqualTo(0.9);
        assertThat(ex.modelVersion()).isEqualTo(MODEL);
    }

    @Test
    void openAiParsesJsonContent() {
        OpenAiProtocolAdapter adapter = new OpenAiProtocolAdapter();
        // choices[0].message.content 是 JSON 字符串（需转义）
        String body = "{\"choices\":[{\"message\":{\"content\":\""
                + "{\\\"reads\\\":[\\\"user\\\"],\\\"writes\\\":[\\\"dw.snap\\\"],"
                + "\\\"columnEdges\\\":[{\\\"srcTable\\\":\\\"user\\\",\\\"srcColumn\\\":\\\"id\\\","
                + "\\\"dstTable\\\":\\\"dw.snap\\\",\\\"dstColumn\\\":\\\"id\\\"}],"
                + "\\\"confidence\\\":0.9}\"}}]}";
        AgentExtraction ex = adapter.parseResponse(body, MODEL);
        assertThat(ex.reads()).containsExactly("user");
        assertThat(ex.writes()).containsExactly("dw.snap");
        assertThat(ex.columnEdges()).hasSize(1);
        assertThat(ex.confidence()).isEqualTo(0.9);
        assertThat(ex.modelVersion()).isEqualTo(MODEL);
    }

    @Test
    void twoProtocolsProduceEquivalentExtraction() {
        AgentExtraction anthropic = new AnthropicProtocolAdapter().parseResponse(anthropicBody(), MODEL);
        AgentExtraction openai = new OpenAiProtocolAdapter().parseResponse(openAiBody(), MODEL);
        assertThat(anthropic.reads()).isEqualTo(openai.reads());
        assertThat(anthropic.writes()).isEqualTo(openai.writes());
        assertThat(anthropic.columnEdges()).isEqualTo(openai.columnEdges());
        assertThat(anthropic.confidence()).isEqualTo(openai.confidence());
    }

    @Test
    void anthropicEmptyOnNoToolUse() {
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"no tool\"}]}";
        AgentExtraction ex = new AnthropicProtocolAdapter().parseResponse(body, MODEL);
        assertThat(ex.reads()).isEmpty();
        assertThat(ex.writes()).isEmpty();
    }

    // ---- buildRequest 端点/鉴权头（协议差异可见）----

    @Test
    void anthropicBuildRequestPointsToMessagesWithApiKeyHeader() {
        LineageExtractionPrompt.LineagePrompt prompt = LineageExtractionPrompt.build("SELECT * FROM user", "SQL");
        HttpRequest req = new AnthropicProtocolAdapter().buildRequest(cfg("ANTHROPIC", "https://api.anthropic.com"), prompt, "key123");
        assertThat(req.uri().toString()).isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(req.headers().firstValue("x-api-key")).contains("key123");
        assertThat(req.headers().firstValue("anthropic-version")).contains("2023-06-01");
        assertThat(req.headers().firstValue("content-type")).contains("application/json");
    }

    @Test
    void openAiBuildRequestPointsToChatCompletionsWithBearer() {
        LineageExtractionPrompt.LineagePrompt prompt = LineageExtractionPrompt.build("SELECT * FROM user", "SQL");
        HttpRequest req = new OpenAiProtocolAdapter().buildRequest(cfg("OPENAI", "https://api.openai.com"), prompt, "sk-key");
        assertThat(req.uri().toString()).isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(req.headers().firstValue("Authorization")).contains("Bearer sk-key");
        assertThat(req.headers().firstValue("content-type")).contains("application/json");
    }

    @Test
    void buildRequestOmitsAuthHeaderWhenNoKey() {
        LineageExtractionPrompt.LineagePrompt prompt = LineageExtractionPrompt.build("SELECT 1", "SQL");
        HttpRequest req = new OpenAiProtocolAdapter().buildRequest(cfg("OPENAI", "https://api.openai.com"), prompt, null);
        assertThat(req.headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    void buildRequestStripsTrailingSlash() {
        LineageExtractionPrompt.LineagePrompt prompt = LineageExtractionPrompt.build("SELECT 1", "SQL");
        HttpRequest req = new AnthropicProtocolAdapter().buildRequest(cfg("ANTHROPIC", "https://api.anthropic.com/"), prompt, "k");
        assertThat(req.uri().toString()).isEqualTo("https://api.anthropic.com/v1/messages");
    }

    /**
     * P1 回归锁：Anthropic 请求体 max_tokens 须足够大，不得回退到会截断 reasoning 模型思维链的 1024。
     */
    @Test
    void anthropicRequestBodyHasGenerousMaxTokens() {
        LineageExtractionPrompt.LineagePrompt prompt = LineageExtractionPrompt.build("SELECT * FROM user", "SQL");
        HttpRequest req = new AnthropicProtocolAdapter().buildRequest(cfg("ANTHROPIC", "https://api.anthropic.com"), prompt, "k");
        String body = bodyOf(req);
        assertThat(body).contains("\"max_tokens\":8192");
        assertThat(body).doesNotContain("\"max_tokens\":1024");
    }

    /** 从 HttpRequest 的 BodyPublisher 同步读回请求体字符串（JDK HttpClient 无内建 getter）。 */
    private static String bodyOf(HttpRequest req) {
        var publisher = req.bodyPublisher().orElseThrow();
        var buf = new StringBuilder();
        var latch = new java.util.concurrent.CountDownLatch(1);
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(java.nio.ByteBuffer b) {
                buf.append(java.nio.charset.StandardCharsets.UTF_8.decode(b));
            }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });
        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return buf.toString();
    }

    private static String anthropicBody() {
        return "{\"content\":[{\"type\":\"tool_use\",\"name\":\"emit_lineage\","
                + "\"input\":{\"reads\":[\"user\"],\"writes\":[\"dw.snap\"],"
                + "\"columnEdges\":[],\"confidence\":0.8}}]}";
    }

    private static String openAiBody() {
        return "{\"choices\":[{\"message\":{\"content\":\""
                + "{\\\"reads\\\":[\\\"user\\\"],\\\"writes\\\":[\\\"dw.snap\\\"],"
                + "\\\"columnEdges\\\":[],\\\"confidence\\\":0.8}\"}}]}";
    }
}
