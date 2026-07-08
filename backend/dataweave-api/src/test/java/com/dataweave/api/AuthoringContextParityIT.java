package com.dataweave.api;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 058 T019（US1 / SC-006）：双面等价 IT。
 * 同一已 push 任务，经 REST {@code GET /api/authoring-context/{id}} 与 MCP
 * {@code query_authoring_context} 两路，返回读写表集合语义一致——证两面同源、零漂移。
 *
 * <p>测试任务 {@code datasource_id=NULL}：接地探针立即返回 UNKNOWN（不做不可达实时探测），
 * neo4j 邻居查询不可达时快速降级——两面同等，parity 不受外部系统在线与否影响。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class AuthoringContextParityIT {

    private static final String MCP_TOKEN = "dataweave-local-mcp-token";
    private static final long TASK_ID = 970001L;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        // 无绑定数据源的 SQL 任务：读 t_in、写 t_out（接地走 UNKNOWN，规避不可达源实时探测）
        jdbc.update("DELETE FROM task_def WHERE id = ?", TASK_ID);
        jdbc.update("INSERT INTO task_def (id, tenant_id, project_id, name, type, content, "
                + "datasource_id, target_datasource_id, status, current_version_no, has_draft_change, "
                + "created_by, updated_by, created_at, updated_at, deleted, version) VALUES "
                + "(?, 1, 1, 'parity-it', 'SQL', 'insert into t_out select * from t_in', "
                + "NULL, NULL, 'ONLINE', 1, 0, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)", TASK_ID);

        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(20))
                .build();
    }

    @Test
    void restAndMcp_agreeOnReadsWrites() throws Exception {
        // ① REST（JWT + projectId=1）
        byte[] restBody = client.get()
                .uri(b -> b.path("/api/authoring-context/" + TASK_ID).queryParam("projectId", 1).build())
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer(jwtUtil))
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        JsonNode restData = mapper.readTree(restBody).get("data");
        Set<String> restTables = tableSet(restData);

        // ② MCP（Bearer mcp token，租户域）
        byte[] mcpBody = client.post().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + MCP_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "query_authoring_context",
                                "arguments", Map.of("taskDefId", String.valueOf(TASK_ID)))))
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        JsonNode mcpRoot = mapper.readTree(mcpBody);
        assertThat(mcpRoot.get("result").get("isError").asBoolean()).isFalse();
        String text = mcpRoot.get("result").get("content").get(0).get("text").asString();
        Set<String> mcpTables = tableSet(mapper.readTree(text));

        // 核心：两面读写表集合一致（SC-006）
        assertThat(mcpTables).isEqualTo(restTables);
        // 语义健全：insert into t_out select ... from t_in
        assertThat(restTables).contains("t_in", "t_out");
    }

    private Set<String> tableSet(JsonNode ctx) {
        Set<String> s = new TreeSet<>();
        collect(ctx.get("reads"), s);
        collect(ctx.get("writes"), s);
        return s;
    }

    private void collect(JsonNode arr, Set<String> into) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode n : arr) {
            JsonNode t = n.get("table");
            if (t != null) into.add(t.asString());
        }
    }
}
