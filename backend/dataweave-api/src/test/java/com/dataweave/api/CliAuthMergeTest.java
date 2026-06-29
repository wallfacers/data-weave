package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * dw 认证合并回归测试（FR-015）。
 *
 * <p>验证：统一 Bearer 凭据对 CLI 端点和 ops 端点均通过认证层；
 * 缺/错凭据返回 401；过渡期 X-DW-Token 双接受。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class CliAuthMergeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    // ---- 统一 Bearer 对 CLI 端点通过认证 ----

    @Test
    void cliTasksReadWithBearerShouldReturn200() {
        webTestClient.get()
                .uri("/api/cli/tasks")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer(jwtUtil))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);
    }

    @Test
    void cliTaskByIdWithBearerShouldPassAuth() {
        // 大 ID 大概率不存在；但即使存在返回 200 也证明认证已通过
        // 关键断言：非 401
        var status = webTestClient.get()
                .uri("/api/cli/tasks/99999")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer(jwtUtil))
                .exchange()
                .expectStatus().value(v -> {
                    // 认证通过则不会是 401；业务返回 200 或 404 都是正常的
                    assert v != 401 : "Bearer 凭据应通过认证层，不应返回 401";
                })
                .returnResult(String.class)
                .getStatus();
        assert status.is2xxSuccessful() || status.value() == 404;
    }

    // ---- 统一 Bearer 对 ops 端点通过认证 ----

    @Test
    void opsMetricsWithBearerShouldReturn200() {
        webTestClient.get()
                .uri("/api/ops/metrics")
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer(jwtUtil))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);
    }

    // ---- 缺凭据 → 401 ----

    @Test
    void cliEndpointWithoutTokenShouldReturn401() {
        webTestClient.get()
                .uri("/api/cli/tasks")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void opsEndpointWithoutTokenShouldReturn401() {
        webTestClient.get()
                .uri("/api/ops/metrics")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---- 错凭据 → 401 ----

    @Test
    void cliEndpointWithInvalidTokenShouldReturn401() {
        webTestClient.get()
                .uri("/api/cli/tasks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid.jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void opsEndpointWithInvalidTokenShouldReturn401() {
        webTestClient.get()
                .uri("/api/ops/metrics")
                .header(HttpHeaders.AUTHORIZATION, "Bearer garbage-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---- 过渡期 X-DW-Token 双接受（CLI 端点） ----

    @Test
    void cliReadEndpointWithXDwTokenShouldStillWork() {
        webTestClient.get()
                .uri("/api/cli/tasks")
                .header("X-DW-Token", "dataweave-local-cli-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);
    }
}
