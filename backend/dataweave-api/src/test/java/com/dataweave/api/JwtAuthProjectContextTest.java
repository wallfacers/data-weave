package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 036 收口：{@code JwtAuthFilter} 在请求不携带 {@code X-Project-Id} 头 / {@code ?projectId=}
 * 时，projectId 解析为 null。回归此前 filter 对 null 值做 {@code ConcurrentHashMap.put}
 * 触发 NPE（→ 500）的缺陷：无项目上下文的受保护端点（如列项目）必须正常放行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class JwtAuthProjectContextTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        // 仅带 Bearer，刻意不带 X-Project-Id 头，也不带 ?projectId= —— projectId 将为 null。
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
    }

    @Test
    void requestWithoutProjectContext_passesFilterWithoutNpe() {
        // GET /api/projects 无参列出当前租户项目，不依赖 projectId。
        // 修复前：filter 第 140 行 put(null) 抛 NPE → 500。修复后：200。
        client.get()
                .uri("/api/projects")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);
    }
}
