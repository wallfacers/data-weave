package com.dataweave.api.application.bridge;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归保护：workhorse {@code POST /v1/sessions} 要求 metadata 为字符串字典，非字符串值会被拒 400
 * （曾因 {@code {"headless": true}} 致诊断会话建立失败回落 mock，仅在活体暴露）。
 *
 * <p>用 reactor-netty 起本地 server 捕获 {@link WorkhorseHttpClient#createSession} <b>实际发出的 JSON body</b>，
 * 断言 metadata 值被 coerce 成带引号的字符串——若有人移除/改坏 coerce，本测试即红，不必等到活体。
 */
class WorkhorseHttpClientMetadataTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createSession_coercesMetadataValuesToQuotedStrings() {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .route(routes -> routes.post("/v1/sessions", (req, res) ->
                        req.receive().aggregate().asString().defaultIfEmpty("").flatMap(body -> {
                            capturedBody.set(body);
                            return res.header("content-type", "application/json")
                                    .sendString(Mono.just("{\"id\":\"sess-1\"}")).then();
                        })))
                .bindNow();
        try {
            WorkhorseHttpClient client = new WorkhorseHttpClient(
                    WebClient.builder(), objectMapper, "http://127.0.0.1:" + server.port());

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("headless", true);   // boolean —— 修复前会原样序列化成裸 true 致 400
            metadata.put("retries", 3);        // number

            String id = client.createSession("you are a diagnoser", metadata);

            assertThat(id).isEqualTo("sess-1");
            String body = capturedBody.get();
            assertThat(body).isNotNull();
            // 值必须是带引号的字符串，绝不能是裸 boolean/number
            assertThat(body).contains("\"headless\":\"true\"").contains("\"retries\":\"3\"");
            assertThat(body).doesNotContain("\"headless\":true").doesNotContain("\"retries\":3");
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void stringifyValues_coercesAllValuesToString() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("b", true);
        in.put("n", 42);
        in.put("s", "x");
        in.put("nil", null);

        Map<String, Object> out = WorkhorseHttpClient.stringifyValues(in);

        assertThat(out).containsEntry("b", "true").containsEntry("n", "42")
                .containsEntry("s", "x").containsEntry("nil", "");
        out.values().forEach(v -> assertThat(v).isInstanceOf(String.class));
    }

    @Test
    void stringifyValues_nullOrEmpty_returnsEmpty() {
        assertThat(WorkhorseHttpClient.stringifyValues(null)).isEmpty();
        assertThat(WorkhorseHttpClient.stringifyValues(Map.of())).isEmpty();
    }
}
