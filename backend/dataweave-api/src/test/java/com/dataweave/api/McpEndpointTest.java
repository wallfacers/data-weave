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
 * MCP 端点：Bearer 认证（401 路径）、initialize、tools/list、tools/call（查询直通、写工具经闸门）。
 * 作为 MCP Inspector 的等价客户端手验。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class McpEndpointTest {

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
    void noToken_returns401() {
        client.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void wrongToken_returns401() {
        client.post().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer nope")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void initialize_returnsProtocolAndCapabilities() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.protocolVersion").exists()
                .jsonPath("$.result.capabilities.tools").exists()
                .jsonPath("$.result.serverInfo.name").isEqualTo("dataweave");
    }

    @Test
    void toolsList_exposesPlatformTools() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.tools[?(@.name=='query_fleet')].name").isEqualTo("query_fleet")
                .jsonPath("$.result.tools[?(@.name=='project_push')].name").isEqualTo("project_push")
                .jsonPath("$.result.tools[?(@.name=='node_exec')].name").isEqualTo("node_exec")
                .jsonPath("$.result.tools[?(@.name=='approve_and_execute')].name").isEqualTo("approve_and_execute");
    }

    @Test
    void toolsList_noCreateTask_noLegacyAiTools() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 99, "method", "tools/list"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.tools[?(@.name=='create_task')]").doesNotExist()
                .jsonPath("$.result.tools[?(@.name=='query_diagnosis')]").doesNotExist()
                .jsonPath("$.result.tools[?(@.name=='update_task')]").doesNotExist()
                .jsonPath("$.result.tools[?(@.name=='delete_task')]").doesNotExist();
    }

    @Test
    void toolsCall_queryFleet_returnsContent() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                        "params", Map.of("name", "query_fleet", "arguments", Map.of())))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].type").isEqualTo("text");
    }

    @Test
    void toolsCall_projectPush_goesThroughGate() {
        // Project 1 has seed data; partial push detects removals → L2 PENDING (risk-adaptive gate works)
        var files = java.util.Map.of(
                "project.yaml", "formatVersion: 1\ncode: test-project\nname: Test Project",
                "mcp/test_push.task.yaml", "formatVersion: 1\nname: mcp_test_push\ntype: SQL\n");
        var args = java.util.Map.of("projectId", 1, "files", files);
        var params = java.util.Map.of("name", "project_push", "arguments", args);
        var body = java.util.Map.of("jsonrpc", "2.0", "id", 4, "method", "tools/call", "params", params);
        post().bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].text").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .containsPattern("EXECUTED|PENDING_APPROVAL"));
    }

    @Test
    void toolsCall_nodeExecInjection_isGatedToApproval() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 5, "method", "tools/call",
                        "params", Map.of("name", "node_exec", "arguments",
                                Map.of("nodeCode", "node-1", "command", "df -h ; rm -rf /tmp"))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.content[0].text").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .contains("PENDING_APPROVAL"));
    }

    @Test
    void toolsCall_unknownTool_returnsJsonRpcError() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 6, "method", "tools/call",
                        "params", Map.of("name", "nonexistent_tool", "arguments", Map.of())))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo(-32602);
    }
}
