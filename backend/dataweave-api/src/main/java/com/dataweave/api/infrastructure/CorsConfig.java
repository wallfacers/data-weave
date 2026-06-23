package com.dataweave.api.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * WebFlux 全局 CORS，允许前端跨域访问 /api/** 与 /agui（SSE 直连后端必须放行前端源）。
 *
 * <p>允许的源可配：{@code app.cors.allowed-origins}（逗号分隔），默认 {@code http://localhost:4000}（dev）。
 * 生产部署须设为浏览器实际访问的前端域名，例如 {@code app.cors.allowed-origins=https://app.example.com}。
 * 因 {@code allowCredentials=true}，源必须是明确列表，不能用 {@code *}。
 *
 * <p>allowedMethods 必须含 {@code PATCH}：类目归类移动（{@code /api/{tasks|workflows}/{id}/catalog}）、
 * 文件夹改名/移动（{@code /api/catalog/nodes/{id}}）等写操作均走 PATCH。浏览器对 PATCH 必带
 * {@code Origin} 头（且触发 preflight），缺 PATCH 会被 {@link CorsWebFilter} 直接 403，
 * 表现为前端拖拽/改名「移动失败」。
 */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(
            @Value("${app.cors.allowed-origins:http://localhost:4000}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
