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
 * MCP 端点 Bearer 认证 + 身份绑定（E1）。
 * 校验 /mcp 路径 Bearer token；校验通过后从配置解析绑定的 tenant/user 身份，
 * 置入 exchange 属性供 {@link com.dataweave.api.interfaces.McpController} 在分发工具前注入 TenantContext。
 *
 * <p>token / tenant / user 经配置注入（{@code mcp.auth.*})。默认值仅用于本地开发，部署须覆盖。
 */
@Component
@Order(-100)
public class McpAuthFilter implements WebFilter {

    public static final String ATTR_TENANT_ID = "mcp.tenantId";
    public static final String ATTR_USER_ID = "mcp.userId";

    private final String token;
    private final Long tenantId;
    private final Long userId;

    public McpAuthFilter(@Value("${mcp.auth.token:dataweave-local-mcp-token}") String token,
                         @Value("${mcp.auth.tenant-id:1}") Long tenantId,
                         @Value("${mcp.auth.user-id:1}") Long userId) {
        this.token = token;
        this.tenantId = tenantId;
        this.userId = userId;
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
        // E1: token 校验通过，将绑定的租户/用户身份置入 exchange 属性
        exchange.getAttributes().put(ATTR_TENANT_ID, tenantId);
        exchange.getAttributes().put(ATTR_USER_ID, userId);
        return chain.filter(exchange);
    }
}
