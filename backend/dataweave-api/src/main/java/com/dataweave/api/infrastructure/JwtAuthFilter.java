package com.dataweave.api.infrastructure;

import io.jsonwebtoken.Claims;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsUtils;
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
 *   <li>{@code /mcp} — MCP 端点（由 McpAuthFilter 独立守护）</li>
 *   <li>{@code /api/cli} — CLI 端点：优先 Bearer JWT，若仅携带 X-DW-Token 则放行至 CliController 自行校验（过渡期双接受）</li>
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
            "/mcp",
            "/api/health",
            "/api/cluster",
            "/api/fleet"
    );

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // CORS 预检（OPTIONS + Origin + Access-Control-Request-Method）必须放行：
        // 预检请求不带凭证，应由 CorsWebFilter 应答；本 filter @Order(-90) 先于 CorsWebFilter，
        // 若不豁免会对 /api/** 的预检直接 401，导致浏览器对 PATCH/DELETE 等的预检失败。
        if (CorsUtils.isPreFlightRequest(exchange.getRequest())) {
            return chain.filter(exchange);
        }

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

        // 过渡期兼容：/api/cli 端点若无 Bearer token，但携带 X-DW-Token header，
        // 则放行至 CliController 自行校验（保持旧 CLI 兼容）。
        if (token == null && path.startsWith("/api/cli")) {
            String xDwToken = exchange.getRequest().getHeaders().getFirst("X-DW-Token");
            if (xDwToken != null && !xDwToken.isBlank()) {
                return chain.filter(exchange);
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
