package com.dataweave.api.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SseNoBufferingWebFilter 单元测试：SSE 响应加防缓冲头，非 SSE 响应不受影响。
 */
class SseNoBufferingWebFilterTest {

    private final SseNoBufferingWebFilter filter = new SseNoBufferingWebFilter();

    @Test
    void addsNoBufferingHeadersForSse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/ops/instances/x/logs/stream"));
        exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        // chain 触发响应提交 → beforeCommit 回调执行
        WebFilterChain chain = ex -> ex.getResponse().setComplete();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(exchange.getResponse().getHeaders().getCacheControl()).contains("no-cache");
    }

    @Test
    void leavesJsonResponseUntouched() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/tasks"));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        WebFilterChain chain = ex -> ex.getResponse().setComplete();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Accel-Buffering")).isNull();
    }
}
