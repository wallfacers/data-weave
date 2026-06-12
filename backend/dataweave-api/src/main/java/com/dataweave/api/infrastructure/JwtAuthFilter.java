package com.dataweave.api.infrastructure;

import io.jsonwebtoken.Claims;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * JWT 认证 WebFilter。对 {@code /api/**}（排除白名单）校验 Bearer JWT；
 * 解析成功后将 userId / tenantId / roles 写入 exchange attributes，供下游读取。
 *
 * <p>白名单路径（无需 JWT）：
 * <ul>
 *   <li>{@code /api/auth/login} — 登录</li>
 *   <li>{@code /agui} — AG-UI SSE（保留原有无鉴权行为）</li>
 *   <li>{@code /mcp} — MCP 端点（由 McpAuthFilter 独立守护）</li>
 * </ul>
 */
@Component
@Order(-90)
public class JwtAuthFilter implements WebFilter {

    private static final Set<String> WHITELIST = Set.of(
            "/api/auth/login",
            "/api/auth/me"
    );

    private static final Set<String> PREFIX_WHITELIST = Set.of(
            "/agui",
            "/mcp",
            "/api/health",
            "/api/cluster"
    );

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 非 /api 路径或白名单路径，跳过
        if (!path.startsWith("/api")) {
            return chain.filter(exchange);
        }
        if (WHITELIST.contains(path)) {
            return chain.filter(exchange);
        }
        for (String prefix : PREFIX_WHITELIST) {
            if (path.startsWith(prefix)) {
                return chain.filter(exchange);
            }
        }

        // 提取 Bearer token（header 优先，query param 兜底——EventSource 不支持自定义 header）
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String token = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring(7).trim();
        } else {
            // SSE 端点兜底：从 ?token= 读取
            String queryToken = exchange.getRequest().getQueryParams().getFirst("token");
            if (queryToken != null && !queryToken.isBlank()) {
                token = queryToken.trim();
            }
        }
        if (token == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        Claims claims = jwtUtil.parse(token);
        if (claims == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // 将认证信息写入 exchange attributes + TenantContext（ThreadLocal）
        Long userId = jwtUtil.userId(claims);
        Long tenantId = jwtUtil.tenantId(claims);
        String username = jwtUtil.username(claims);

        exchange.getAttributes().put("userId", userId);
        exchange.getAttributes().put("tenantId", tenantId);
        exchange.getAttributes().put("username", username);
        @SuppressWarnings("unchecked")
        List<String> roles = jwtUtil.roles(claims);
        exchange.getAttributes().put("roles", roles);

        TenantContext.set(tenantId, userId, username);

        return chain.filter(exchange)
                .doFinally(signal -> TenantContext.clear());
    }
}
