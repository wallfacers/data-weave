package com.dataweave.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健康检查 + CORS 预检测试。
 *
 * <p>验证 GET /api/health 返回正确状态，以及 OPTIONS /agui 的 CORS 头。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
                .uri("/api/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("ok")
                .returnResult()
                .getResponseBody();

        // jsonPath 已断言 status==ok；这里再确认响应体非空
        assertThat(body).isNotNull();
    }

    @Test
    void corsPreflightShouldAllowLocalhost3000() {
        webTestClient.options()
                .uri("/agui")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isOk()
                .expectHeader()
                .value(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        value -> assertThat(value).isEqualTo("http://localhost:3000"));
    }
}
