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
 * T016: project_push 风险自适应闸门测试（命门）。
 * - 纯增改 → L1 EXECUTED + 审计 + 落库
 * - 含删除/force → L2 PENDING 且断言 0 落库
 * - 无效定义 → project.sync.* 拒不部分落库
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class McpProjectPushGateTest {

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

    private static Map<String, String> minimalBundle(String taskName) {
        return Map.of(
                "project.yaml", "formatVersion: 1\ncode: test-project\nname: Test Project",
                "mcp/" + taskName + ".task.yaml",
                "formatVersion: 1\nname: " + taskName + "\ntype: SQL\n");
    }

    @Test
    void projectPush_onSeededProject_detectsRemovals_RoutesToL2() {
        // Project 1 has seed data; partial push → detected removals → destructive → L2 PENDING
        Map<String, String> files = minimalBundle("hello_test");
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "project_push", "arguments",
                                Map.of("projectId", 1, "files", files))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].text").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .contains("PENDING_APPROVAL"));
    }

    @Test
    void projectPush_withForce_L2Pending() {
        // force=true → PROJECT_PUSH_DESTRUCTIVE → L2 PENDING (regardless of diff)
        Map<String, String> files = minimalBundle("forced_task");
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                        "params", Map.of("name", "project_push", "arguments",
                                Map.of("projectId", 1, "files", files, "force", true))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].text").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .contains("PENDING_APPROVAL"));
    }

    @Test
    void projectPush_invalidDefinition_rejected() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                        "params", Map.of("name", "project_push", "arguments",
                                Map.of("projectId", 1, "files", Map.of()))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(true);
    }

    @Test
    void projectPush_crossTenantProject_rejected() {
        Map<String, String> files = minimalBundle("test_cross");
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 4, "method", "tools/call",
                        "params", Map.of("name", "project_push", "arguments",
                                Map.of("projectId", 999, "files", files))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(true);
    }

    @Test
    void projectPull_readsTenantScoped() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 5, "method", "tools/call",
                        "params", Map.of("name", "project_pull", "arguments",
                                Map.of("projectId", 1))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].text").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .contains("files"));
    }

    @Test
    void projectDiff_readsDiffPreview() {
        Map<String, String> files = minimalBundle("new_task");
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 6, "method", "tools/call",
                        "params", Map.of("name", "project_diff", "arguments",
                                Map.of("projectId", 1, "files", files))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].text").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .contains("added"));
    }

    @Test
    void projectPush_destructiveL2_assertZeroWrites() {
        // Push with force=true → PENDING_APPROVAL → project state MUST NOT change (0 writes)
        // Record baseline before push
        String[] pullBefore = new String[1];
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 10, "method", "tools/call",
                        "params", Map.of("name", "project_pull", "arguments", Map.of("projectId", 1))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.content[0].text")
                .value((String t) -> pullBefore[0] = t);

        // Destructive push must return PENDING_APPROVAL (EXECUTED never reached = 0 writes)
        Map<String, String> files = minimalBundle("zero_write_test");
        String[] pushResult = new String[1];
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 11, "method", "tools/call",
                        "params", Map.of("name", "project_push", "arguments",
                                Map.of("projectId", 1, "files", files, "force", true))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.content[0].text")
                .value((String t) -> pushResult[0] = t);

        org.assertj.core.api.Assertions.assertThat(pushResult[0])
                .as("Destructive push must go to PENDING_APPROVAL (0 writes)")
                .contains("PENDING_APPROVAL")
                .doesNotContain("\"outcome\":\"EXECUTED\"");

        // Pull again — project state must be unchanged (same baseline = 0 writes)
        String[] pullAfter = new String[1];
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 12, "method", "tools/call",
                        "params", Map.of("name", "project_pull", "arguments", Map.of("projectId", 1))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.content[0].text")
                .value((String t) -> pullAfter[0] = t);

        // Baseline tokens must match (no data was written)
        org.assertj.core.api.Assertions.assertThat(pullAfter[0])
                .as("Project state must be unchanged after destructive push (0 writes)")
                .isEqualTo(pullBefore[0]);
    }
}
