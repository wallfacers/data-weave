package com.dataweave.api.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * MCP 端点 Bearer 认证。仅校验 /mcp 路径：缺失或错误 token → 401，不进入工具逻辑。
 *
 * <p>token 经配置注入（{@code mcp.auth.token}）。默认值仅用于本地开发，部署须覆盖。
 */
@Component
@Order(-100)
public class McpAuthFilter implements WebFilter {

    private final String token;

    public McpAuthFilter(@Value("${mcp.auth.token:dataweave-local-mcp-token}") String token) {
        this.token = token;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/mcp")) {
            return chain.filter(exchange);
        }
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ") || !auth.substring(7).trim().equals(token)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
