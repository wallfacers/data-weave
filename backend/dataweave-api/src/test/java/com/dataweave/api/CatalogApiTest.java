package com.dataweave.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.dataweave.api.infrastructure.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类目 / 标签 端到端测试（h2 profile）：端点状态码与错误码（409/400）、
 * PATCH null vs 缺失语义、列表无参回归。HTTP 状态统一 200，业务态读 code。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class CatalogApiTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtUtil jwtUtil;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    WebTestClient client;

    @BeforeEach
    void setUp() {
        // 036：catalog/tag 端点经 ProjectScope.require 真成员校验——本测试各用例用独立项目
        // 8101~8105 相互隔离，须先造真项目 + user1 成员行（幂等：先删后插），否则 project.forbidden。
        jdbc.update("DELETE FROM project_member WHERE project_id BETWEEN 8101 AND 8105");
        jdbc.update("DELETE FROM projects WHERE id BETWEEN 8101 AND 8105");
        for (long pid = 8101L; pid <= 8105L; pid++) {
            jdbc.update("INSERT INTO projects (id, tenant_id, code, name, owner_id, status, "
                    + "created_by, updated_by, created_at, updated_at, deleted, version) VALUES "
                    + "(?, 1, ?, ?, 1, 'ACTIVE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', "
                    + "TIMESTAMP '2026-06-01 00:00:00', 0, 0)", pid, "cat-p" + pid, "catalog 测试项目 " + pid);
            jdbc.update("INSERT INTO project_member (id, tenant_id, project_id, user_id, role_id, "
                    + "created_by, updated_by, created_at, updated_at, deleted, version) VALUES "
                    + "(?, 1, ?, 1, 1, 1, 1, TIMESTAMP '2026-06-01 00:00:00', "
                    + "TIMESTAMP '2026-06-01 00:00:00', 0, 0)", pid, pid);
        }
        // 固定 id 插入会把 H2 IDENTITY 计数器停在 8105，避免后续自增撞主键（仅 H2 测试环境）
        jdbc.execute("ALTER TABLE projects ALTER COLUMN id RESTART WITH 100000");
        jdbc.execute("ALTER TABLE project_member ALTER COLUMN id RESTART WITH 100000");

        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .defaultHeader("X-Project-Id", "1")
                .build();
    }

    @Test
    void createTree_andReadCounts() throws Exception {
        long project = 8101L;
        long root = createFolder(project, null, "数仓");
        createFolder(project, root, "ODS");

        client.get().uri("/api/catalog/tree?projectId={p}", project)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.roots.length()").isEqualTo(1)
                .jsonPath("$.data.roots[0].children.length()").isEqualTo(1);
    }

    @Test
    void deleteNonEmptyFolder_returns409() throws Exception {
        long project = 8102L;
        long parent = createFolder(project, null, "父");
        createFolder(project, parent, "子");

        client.delete().uri("/api/catalog/nodes/{id}", parent)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(409)
                .jsonPath("$.errorCode").isEqualTo("catalog.node.not_empty");
    }

    @Test
    void moveIntoDescendant_returns400Cycle() throws Exception {
        long project = 8103L;
        long a = createFolder(project, null, "A");
        long b = createFolder(project, a, "B");

        client.patch().uri("/api/catalog/nodes/{id}", a)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("parentId", b))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(400)
                .jsonPath("$.errorCode").isEqualTo("catalog.cycle");
    }

    @Test
    void rejectPathField_returns400() throws Exception {
        long project = 8104L;
        long a = createFolder(project, null, "A");
        client.patch().uri("/api/catalog/nodes/{id}", a)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("path", "/999/"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(400);
    }

    @Test
    void tagDuplicate_returns409() {
        long project = 8105L;
        Map<String, Object> body = Map.of("projectId", project, "name", "重复标签");
        client.post().uri("/api/tags").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.code").isEqualTo(0);
        client.post().uri("/api/tags").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo(409)
                .jsonPath("$.errorCode").isEqualTo("tag.name.duplicate");
    }

    @Test
    void assignCatalog_nullClears_missingNoChange() throws Exception {
        // 用既有 seed 任务 id=2（project 1，deleted=0 的 live 任务；task 1 在 seed 已软删除 deleted=1，
        // 不会出现在 /api/tasks 的 WHERE deleted=0 列表里，故归类语义验证须用 live 任务）
        long folder = createFolder(1L, null, "归类目标-" + port);

        // 给值 → 归入
        client.patch().uri("/api/tasks/2/catalog").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("catalogNodeId", folder))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.code").isEqualTo(0);
        assertThat(catalogOfTask(2)).isEqualTo(folder);

        // 字段缺失 {} → 不改
        client.patch().uri("/api/tasks/2/catalog").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.code").isEqualTo(0);
        assertThat(catalogOfTask(2)).isEqualTo(folder);

        // 显式 null → 清空
        Map<String, Object> nullBody = new HashMap<>();
        nullBody.put("catalogNodeId", null);
        client.patch().uri("/api/tasks/2/catalog").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(nullBody)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.code").isEqualTo(0);
        assertThat(catalogOfTask(2)).isNull();
    }

    @Test
    void taskList_withoutNewParams_regression() {
        // 4.8：不带类目/标签参数，行为与既有一致
        client.get().uri("/api/tasks?page=0&size=10")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.content").exists();
    }

    // ─── helpers ─────────────────────────────────────────

    private long createFolder(Long projectId, Long parentId, String name) throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (projectId != null) body.put("projectId", projectId);
        if (parentId != null) body.put("parentId", parentId);
        body.put("name", name);
        byte[] resp = client.post().uri("/api/catalog/nodes")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(0)
                .returnResult().getResponseBody();
        JsonNode node = objectMapper.readTree(resp).get("data");
        return node.get("id").asLong();
    }

    private Long catalogOfTask(long taskId) {
        byte[] resp = client.get().uri("/api/tasks?page=0&size=500")
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        try {
            JsonNode content = objectMapper.readTree(resp).get("data").get("content");
            for (JsonNode t : content) {
                if (t.get("id").asLong() == taskId) {
                    JsonNode c = t.get("catalogNodeId");
                    return c == null || c.isNull() ? null : c.asLong();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
