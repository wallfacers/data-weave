package com.dataweave.api.application.bridge;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归保护：workhorse 会话上一轮被打断会卡在 thinking/running，提交新消息被拒以 409 session_busy。
 * 修复前该会话所有后续消息都 409 → /agui 的 Flux 报错 → 连接重置 → 该对话永久空白
 * （活体复现：浏览器 ERR_INCOMPLETE_CHUNKED_ENCODING）。
 *
 * <p>本测试用 reactor-netty 起本地 server，首个 submit 返 409、随后断言客户端先打 {@code /cancel} 再重试 submit
 * （第二次 202）—— 自愈链路若被移除/改坏即红。
 */
class WorkhorseHttpClientSessionBusyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void submit_on409SessionBusy_cancelsThenRetries() {
        List<String> calls = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger submitN = new java.util.concurrent.atomic.AtomicInteger();

        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .route(routes -> routes
                        // submit：第一次 409 session_busy，第二次 202
                        .post("/v1/sessions/{id}/stream", (req, res) -> {
                            int n = submitN.incrementAndGet();
                            calls.add("submit#" + n);
                            if (n == 1) {
                                return res.status(409).header("content-type", "application/json")
                                        .sendString(Mono.just(
                                                "{\"code\":\"session_busy\",\"state\":\"thinking\"}")).then();
                            }
                            return res.status(202).send().then();
                        })
                        // cancel：解卡
                        .post("/v1/sessions/{id}/cancel", (req, res) -> {
                            calls.add("cancel");
                            return res.status(202).send().then();
                        })
                        // 订阅流：提交成功后立即一条 done 终结，让 sendMessage 完成
                        .get("/v1/sessions/{id}/stream", (req, res) ->
                                res.header("content-type", "text/event-stream")
                                        .sendString(Mono.just(
                                                "id: 1\nevent: assistant_text_done\ndata: {\"stop_reason\":\"end_turn\"}\n\n"))
                                        .then()))
                .bindNow();
        try {
            WorkhorseHttpClient client = new WorkhorseHttpClient(
                    WebClient.builder(), objectMapper, "http://127.0.0.1:" + server.port());

            // 应正常完成（不抛错），且调用顺序为 submit#1(409) → cancel → submit#2(202)
            client.sendMessage("sess-stuck", "你好").collectList().block();

            assertThat(calls).containsSubsequence("submit#1", "cancel", "submit#2");
            assertThat(submitN.get()).isEqualTo(2);
        } finally {
            server.disposeNow();
        }
    }
}
