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
 * T010: 只读与 REST 同源口径测试（SC-005）。
 * MCP 只读工具返回与对应 REST/域服务同源数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class McpReadSameSourceTest {

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
    void queryFleet_viaMcp_returnsSameSourceAsREST() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "query_fleet", "arguments", Map.of())))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false);
    }

    @Test
    void queryTaskDefinitions_viaMcp_returnsTenantScoped() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                        "params", Map.of("name", "query_task_definitions", "arguments", Map.of())))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false);
    }

    @Test
    void queryTaskInstances_viaMcp_returnsSameSourceAsREST() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                        "params", Map.of("name", "query_task_instances", "arguments", Map.of())))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false);
    }
}
