package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

/**
 * 036-D 工作流定义写端点项目角色授权（FR-042：工作流定义增删改/上下线 = EDITOR+）。
 * seed：project 1、user1=ADMIN、user2=DEVELOPER；user3=VIEWER 由 setUp 造（同 ProjectRoleAuthzTest）。
 * 另造 project 2（user2 非其成员）+ 归属 project 2 的 workflow_def id=9002，验证跨项目 by-id 守卫。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class WorkflowRoleAuthzTest {

    private static final long TENANT = 1L;
    private static final long PROJECT = 1L;

    @LocalServerPort int port;
    @Autowired JwtUtil jwtUtil;
    @Autowired JdbcTemplate jdbc;

    WebTestClient devClient;     // user2 = DEVELOPER（workflow:manage 持有）
    WebTestClient viewerClient;  // user3 = VIEWER（无权限）

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM project_member WHERE user_id = 3 AND project_id = ?", PROJECT);
        jdbc.update("DELETE FROM users WHERE id = 3");
        jdbc.update("INSERT INTO users (id, tenant_id, username, password_hash, display_name, status, "
                + "created_by, updated_by, created_at, updated_at, deleted, version) VALUES "
                + "(3, 1, 'viewer', '{plain}viewer', 'viewer', 'ACTIVE', 1, 1, "
                + "TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0)");
        // 不用固定 id，让 H2 自增避免与共享库中他已存在的行冲突
        jdbc.update("INSERT INTO project_member (tenant_id, project_id, user_id, role_id, "
                + "created_by, updated_by, created_at, updated_at, deleted, version) VALUES "
                + "(1, ?, 3, 3, 1, 1, TIMESTAMP '2026-06-01 00:00:00', "
                + "TIMESTAMP '2026-06-01 00:00:00', 0, 0)", PROJECT);
        // project 2（user2 非成员）+ 其名下 workflow_def id=9002
        jdbc.update("DELETE FROM workflow_def WHERE id = 9002");
        jdbc.update("DELETE FROM projects WHERE id = 2");
        jdbc.update("INSERT INTO projects (id, tenant_id, code, name, owner_id, status, created_by, "
                + "updated_by, created_at, updated_at, deleted, version) VALUES "
                + "(2, 1, 'p2', 'project-2', 1, 'ACTIVE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', "
                + "TIMESTAMP '2026-06-01 00:00:00', 0, 0)");
        jdbc.update("INSERT INTO workflow_def (id, tenant_id, project_id, name, status, "
                + "current_version_no, has_draft_change, priority, created_at, updated_at, "
                + "deleted, version) VALUES (9002, 1, 2, 'p2-wf', 'DRAFT', "
                + "0, 1, 5, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0)");

        devClient = clientFor(2L, "developer");
        viewerClient = clientFor(3L, "viewer");
    }

    private WebTestClient clientFor(long userId, String name) {
        String bearer = "Bearer " + jwtUtil.generate(userId, TENANT, name, List.of("ADMIN"));
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", bearer)
                .defaultHeader("X-Project-Id", String.valueOf(PROJECT))
                .build();
    }

    @Test
    void viewer_cannot_create_workflow_role_forbidden() {
        viewerClient.post().uri("/api/workflows")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "authz-wf-v"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
    }

    @Test
    void developer_can_create_workflow() {
        devClient.post().uri("/api/workflows")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "authz-wf-d"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);
    }

    @Test
    void developer_cannot_save_dag_of_other_project_forbidden() {
        devClient.put().uri("/api/workflows/9002/dag")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("nodes", List.of(), "edges", List.of()))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.forbidden");
    }

    @Test
    void viewer_cannot_publish_workflow_role_forbidden() {
        // devClient 先建 workflow 取 id，viewerClient POST /{id}/publish → project.role.forbidden
        devClient.post().uri("/api/workflows").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "authz-wf-pub")).exchange();
        Long id = jdbc.queryForObject(
                "SELECT id FROM workflow_def WHERE name = 'authz-wf-pub'", Long.class);
        viewerClient.post().uri("/api/workflows/{id}/publish", id)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
    }
}
