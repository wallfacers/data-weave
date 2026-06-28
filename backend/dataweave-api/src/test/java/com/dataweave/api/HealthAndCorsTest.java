package com.dataweave.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS 预检 + 健康检查回归测试。
 *
 * <p>/agui 已在 Weft AI 拆除中移除，CORS 预检改测 /api/ops/metrics。
 * /api/health 也已在 AI 拆除中移除，改测 /actuator/health。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class HealthAndCorsTest {

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void healthShouldReturnOk() {
        byte[] body = webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .returnResult()
                .getResponseBody();

        // jsonPath 已断言 status==UP；这里再确认响应体非空
        assertThat(body).isNotNull();
    }

    @Test
    void corsPreflightShouldAllowLocalhost4000() {
        webTestClient.options()
                .uri("/api/ops/metrics")
                .header(HttpHeaders.ORIGIN, "http://localhost:4000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader()
                .value(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        value -> assertThat(value).isEqualTo("http://localhost:4000"));
    }
}
