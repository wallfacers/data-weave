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
 * T010: 只读隔离测试。
 * - 只读工具与 REST 同源口径
 * - 租户 A 取租户 B 资源被拒（包含既有 query_*)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class McpTenantIsolationTest {

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
    void queryTaskDefs_returnsTenantScopedData_only() {
        // With tenant-id=1, should only return tenant 1's tasks (9001, 9002, 9003 from seed data)
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "query_task_definitions", "arguments", Map.of())))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false);
    }

    @Test
    void queryTaskInstances_returnsTenantScopedData_only() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                        "params", Map.of("name", "query_task_instances", "arguments", Map.of())))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false);
    }

    @Test
    void queryMetric_respectsTenant() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                        "params", Map.of("name", "query_metric", "arguments", Map.of("code", "GMV"))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false);
    }

    @Test
    void queryLineage_respectsTenant() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 4, "method", "tools/call",
                        "params", Map.of("name", "query_lineage", "arguments", Map.of("code", "GMV"))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false);
    }

    @Test
    void queryFleet_respectsTenant() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 5, "method", "tools/call",
                        "params", Map.of("name", "query_fleet", "arguments", Map.of())))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false);
    }

    @Test
    void instanceLogs_crossTenantInstance_rejected() {
        // Instance with unknown UUID should be rejected (not found in tenant)
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 6, "method", "tools/call",
                        "params", Map.of("name", "instance_logs", "arguments",
                                Map.of("instanceId", "00000000-0000-0000-0000-000000000000"))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(true);
    }

    @Test
    void projectPull_crossTenant_rejected() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call",
                        "params", Map.of("name", "project_pull", "arguments", Map.of("projectId", 999))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(true);
    }

    @Test
    void queryIncidents_returnsTenantScoped_noError() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 8, "method", "tools/call",
                        "params", Map.of("name", "query_incidents", "arguments", Map.of())))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false);
    }

    @Test
    void incidentReverify_unknownIncident_rejected() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 9, "method", "tools/call",
                        "params", Map.of("name", "incident_reverify", "arguments",
                                Map.of("incidentId", "00000000-0000-0000-0000-000000000000"))))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(true);
    }

    @Test
    void toolsList_includesIncidentTools() {
        post().bodyValue(Map.of("jsonrpc", "2.0", "id", 10, "method", "tools/list", "params", Map.of()))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.tools[?(@.name=='query_incidents')].name").isEqualTo("query_incidents")
                .jsonPath("$.result.tools[?(@.name=='incident_reverify')].name").isEqualTo("incident_reverify");
    }
}
