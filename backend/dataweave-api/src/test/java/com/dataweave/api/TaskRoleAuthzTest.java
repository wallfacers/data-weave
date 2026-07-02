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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 036-D 任务定义写端点项目角色授权（FR-042：任务定义增删改/上下线 = EDITOR+）。
 * seed：project 1、user1=ADMIN、user2=DEVELOPER；user3=VIEWER 由 setUp 造（同 ProjectRoleAuthzTest）。
 * 另造 project 2（user2 非其成员）+ 归属 project 2 的 task，验证跨项目 by-id 守卫。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class TaskRoleAuthzTest {

    private static final long TENANT = 1L;
    private static final long PROJECT = 1L;

    @LocalServerPort int port;
    @Autowired JwtUtil jwtUtil;
    @Autowired JdbcTemplate jdbc;

    WebTestClient devClient;     // user2 = DEVELOPER（task:manage 持有）
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
        // project 2（user2 非成员）+ 其名下 task_def id=9001
        jdbc.update("DELETE FROM task_def WHERE id = 9001");
        jdbc.update("DELETE FROM projects WHERE id = 2");
        jdbc.update("INSERT INTO projects (id, tenant_id, code, name, owner_id, status, created_by, "
                + "updated_by, created_at, updated_at, deleted, version) VALUES "
                + "(2, 1, 'p2', 'project-2', 1, 'ACTIVE', 1, 1, TIMESTAMP '2026-06-01 00:00:00', "
                + "TIMESTAMP '2026-06-01 00:00:00', 0, 0)");
        jdbc.update("INSERT INTO task_def (id, tenant_id, project_id, name, type, content, status, "
                + "current_version_no, has_draft_change, retry_max, priority, created_at, updated_at, "
                + "deleted, version) VALUES (9001, 1, 2, 'p2-task', 'PYTHON', 'print(1)', 'DRAFT', "
                + "0, 1, 0, 5, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0)");

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
    void viewer_cannot_create_task_role_forbidden() {
        viewerClient.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "authz-task-v", "type", "PYTHON", "content", "print(1)"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_def WHERE name = 'authz-task-v'", Integer.class);
        assertThat(cnt).isEqualTo(0);
    }

    @Test
    void developer_can_create_task_and_project_is_stamped() {
        devClient.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "authz-task-d", "type", "PYTHON", "content", "print(1)"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.projectId").isEqualTo(1); // 落库打当前项目戳，非硬编码
    }

    @Test
    void developer_cannot_update_task_of_other_project_forbidden() {
        // user2 是 project 1 的 DEVELOPER，但 task 9001 归属 project 2 → 按实体归属拒绝
        devClient.put().uri("/api/tasks/9001")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "hacked", "type", "PYTHON", "content", "print(2)"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.forbidden");
    }

    @Test
    void viewer_cannot_delete_task_role_forbidden() {
        // 先由 developer 造一个 project 1 的任务
        devClient.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "authz-task-del", "type", "PYTHON", "content", "print(1)"))
                .exchange().expectBody().returnResult().getResponseBody();
        Long id = jdbc.queryForObject(
                "SELECT id FROM task_def WHERE name = 'authz-task-del'", Long.class);
        viewerClient.delete().uri("/api/tasks/{id}", id)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
    }

    @Test
    void create_without_project_header_rejected_required() {
        // 不带 X-Project-Id 的写请求 → project.required（越权探测：省略头不能绕过）
        String bearer = "Bearer " + jwtUtil.generate(2L, TENANT, "developer", List.of("ADMIN"));
        WebTestClient bare = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", bearer).build();
        bare.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "authz-task-x", "type", "PYTHON", "content", "print(1)"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("project.required");
    }
}
