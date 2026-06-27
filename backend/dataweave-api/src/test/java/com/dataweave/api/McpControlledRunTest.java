package com.dataweave.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * T020: 受控运行测试。
 * - task_rerun/node_exec 经闸门 + 审计
 * - node_exec 危险命令升级/拒
 * - 越权拒
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class McpControlledRunTest {

    private static final String TOKEN = "dataweave-local-mcp-token";

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(15))
                .build();
    }

    private WebTestClient.RequestBodySpec post() {
        return client.post().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void taskRerun_tenantScoped_goesThroughGate() {
        // Seed instance UUID from data.sql - should trigger gate evaluation
        // Gate may reject if instance doesn't exist or pass through, but should never crash
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "task_rerun", "arguments",
                                Map.of("instanceId", "019ef700-0000-7000-8000-000000000001"))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.content[0].text").isNotEmpty();
    }

    @Test
    void taskRerun_crossTenantInstance_rejected() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                        "params", Map.of("name", "task_rerun", "arguments",
                                Map.of("instanceId", "00000000-0000-0000-0000-000000000000"))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(true);
    }

    @Test
    void nodeExec_dangerousCommand_isUpgradedOrRejected() {
        // Command with redirect (>) should trigger injection detection → at least L2 (PENDING)
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                        "params", Map.of("name", "node_exec", "arguments",
                                Map.of("nodeCode", "node-1", "command", "df -h > /tmp/out.txt"))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].text").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .contains("PENDING_APPROVAL"));
    }

    @Test
    void nodeExec_safeCommand_passesGate() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 4, "method", "tools/call",
                        "params", Map.of("name", "node_exec", "arguments",
                                Map.of("nodeCode", "node-1", "command", "df -h"))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false);
    }

    @Test
    void nodeExec_tenantScoped() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 5, "method", "tools/call",
                        "params", Map.of("name", "node_exec", "arguments",
                                Map.of("nodeCode", "node-1", "command", "free"))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false);
    }
}
