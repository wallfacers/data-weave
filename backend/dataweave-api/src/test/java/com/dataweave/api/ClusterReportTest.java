package com.dataweave.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClusterController /api/cluster/report 端点集成测试（task 3.4）。
 *
 * <p>验证：token 鉴权、三种事件类型（started/finished/failed）、无效请求处理。
 * 项目约定：HTTP 统一 200，错误走 ApiResponse.code 字段。
 * 使用 H2 profile（零外部依赖）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class ClusterReportTest {

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void reportWithoutToken_returnsAuthError() {
        client.post()
                .uri("/api/cluster/report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"event\":\"started\",\"taskInstanceId\":\"" + UUID.randomUUID() + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(401);
    }

    @Test
    void reportWithWrongToken_returnsAuthError() {
        client.post()
                .uri("/api/cluster/report")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"event\":\"started\",\"taskInstanceId\":\"" + UUID.randomUUID() + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(401);
    }

    @Test
    void reportWithInvalidInstanceId_returnsError() {
        client.post()
                .uri("/api/cluster/report")
                .header(HttpHeaders.AUTHORIZATION, "Bearer dataweave-local-cluster-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"event\":\"started\",\"taskInstanceId\":\"not-a-uuid\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(400);
    }

    @Test
    void reportWithInvalidEvent_returnsError() {
        client.post()
                .uri("/api/cluster/report")
                .header(HttpHeaders.AUTHORIZATION, "Bearer dataweave-local-cluster-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"event\":\"unknown\",\"taskInstanceId\":\"" + UUID.randomUUID() + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(400);
    }

    @Test
    void reportStarted_returnsOk() {
        UUID instanceId = UUID.randomUUID();
        // 039 分布式 fencing: reportStarted 返回 CAS 结果——实例不存在则 CAS 失败返回 "stale"，
        // 而非旧版无条件 "reported:started"。worker 据此判断是否仍是当前派单。
        client.post()
                .uri("/api/cluster/report")
                .header(HttpHeaders.AUTHORIZATION, "Bearer dataweave-local-cluster-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"event\":\"started\",\"taskInstanceId\":\"" + instanceId + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data").isEqualTo("stale");  // 实例不存在 → CAS 失败 → stale
    }

    @Test
    void reportFinished_returnsOk() {
        UUID instanceId = UUID.randomUUID();
        client.post()
                .uri("/api/cluster/report")
                .header(HttpHeaders.AUTHORIZATION, "Bearer dataweave-local-cluster-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"event\":\"finished\",\"taskInstanceId\":\"" + instanceId + "\","
                        + "\"exitCode\":0,\"tailLog\":\"success output\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data").isEqualTo("reported:finished");
    }

    @Test
    void reportFailed_returnsOk() {
        UUID instanceId = UUID.randomUUID();
        client.post()
                .uri("/api/cluster/report")
                .header(HttpHeaders.AUTHORIZATION, "Bearer dataweave-local-cluster-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"event\":\"failed\",\"taskInstanceId\":\"" + instanceId + "\","
                        + "\"failureReason\":\"TIMEOUT\",\"tailLog\":\"timed out\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data").isEqualTo("reported:failed");
    }
}
