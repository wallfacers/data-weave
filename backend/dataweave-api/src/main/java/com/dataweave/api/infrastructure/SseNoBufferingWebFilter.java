package com.dataweave.api.infrastructure;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 给所有 SSE（{@code text/event-stream}）响应自动加防缓冲头，确保流式响应不被中间反代缓冲。
 *
 * <p>背景：实时日志（{@code /api/ops/.../logs/stream}）、工作流状态流
 * 等 SSE 经 nginx 等反代时，默认 {@code proxy_buffering on} 会把逐行 SSE 攒到上游关流才一次性下发，
 * 导致「一把输出」而非滚屏。{@code X-Accel-Buffering: no} 是 nginx 识别的「对本响应关闭缓冲」信号
 * （多数反代亦尊重该约定），{@code Cache-Control: no-cache} 防止中间缓存层介入。
 *
 * <p>用 {@code beforeCommit} 回调实现：此刻 controller 已按 {@code produces} 设好 Content-Type，
 * 据此判定 SSE。一处覆盖现有与未来所有 SSE 端点，避免逐端点遗漏。dev 下前端 EventSource 直连后端
 * 绕过 Next 代理是另一道防线，二者互补（见前端 {@code SSE_BASE}）。
 */
@Component
@Order(-80)
public class SseNoBufferingWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            MediaType contentType = headers.getContentType();
            if (contentType != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
                headers.set("X-Accel-Buffering", "no");
                headers.setCacheControl("no-cache");
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
