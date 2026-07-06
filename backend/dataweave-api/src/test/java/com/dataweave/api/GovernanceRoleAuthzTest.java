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
 * 036-D2 治理面写端点项目角色授权（FR-042）：
 * 审批 = project:manage（OWNER only）、push = task:manage（EDITOR+）。
 *
 * <p>seed：project 1、user1=ADMIN（5 权含 project:manage）、user2=DEVELOPER（4 权无 project:manage）；
 * user3=VIEWER 由 setUp 造（无权限）。复用已冻结的 {@code ProjectAuthz} 门面。
 *
 * <p>契约：越权返回 HTTP 200 + {@code $.code=403} + {@code $.errorCode=project.role.forbidden}；
 * 授权放行后闸门照常执行（不弱化）。setUp 范式照抄 {@link ProjectRoleAuthzTest}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class GovernanceRoleAuthzTest {

    private static final long TENANT = 1L;
    private static final long PROJECT = 1L;

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    JdbcTemplate jdbc;

    WebTestClient adminClient;    // user 1 = ADMIN（含 project:manage）
    WebTestClient devClient;      // user 2 = DEVELOPER（metric/task:manage，无 project:manage）
    WebTestClient viewerClient;   // user 3 = VIEWER（无权限，setUp 造）

    @BeforeEach
    void setUp() {
        // 造 user3 = project1 的 VIEWER（幂等：先删后插）；范式同 ProjectRoleAuthzTest。
        jdbc.update("DELETE FROM project_member WHERE user_id = 3 AND project_id = ?", PROJECT);
        jdbc.update("DELETE FROM users WHERE id = 3");
        jdbc.update("INSERT INTO users (id, tenant_id, username, password_hash, display_name, status, "
                + "created_by, updated_by, created_at, updated_at, deleted, version) VALUES "
                + "(3, 1, 'viewer', '{plain}viewer', 'viewer', 'ACTIVE', 1, 1, "
                + "TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0)");
        jdbc.update("INSERT INTO project_member (id, tenant_id, project_id, user_id, role_id, "
                + "created_by, updated_by, created_at, updated_at, deleted, version) VALUES "
                + "(200, 1, ?, 3, 3, 1, 1, TIMESTAMP '2026-06-01 00:00:00', "
                + "TIMESTAMP '2026-06-01 00:00:00', 0, 0)", PROJECT);
        // 推进 H2 IDENTITY 计数器避免后续 repository.save 自增撞主键（仅 H2 测试环境）。
        jdbc.execute("ALTER TABLE project_member ALTER COLUMN id RESTART WITH 100000");

        adminClient = clientFor(1L, "admin");
        devClient = clientFor(2L, "developer");
        viewerClient = clientFor(3L, "viewer");
    }

    private WebTestClient clientFor(long userId, String name) {
        String bearer = "Bearer " + jwtUtil.generate(userId, TENANT, name, List.of("ADMIN"));
        // X-Project-Id 让 JwtAuthFilter 解析 projectId 置入 TenantContext（approval requireCurrent 依赖）。
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", bearer)
                .defaultHeader("X-Project-Id", String.valueOf(PROJECT))
                .build();
    }

    // ===== 审批：project:manage（OWNER only）=====

    @Test
    void developer_cannot_approve_project_manage_only() {
        // DEVELOPER 无 project:manage → 越权（审批 = OWNER only）
        devClient.post().uri("/api/approvals/1/approve")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
    }

    @Test
    void admin_approve_reaches_service() {
        // ADMIN 有 project:manage → 授权放行；审批单 999999 不存在 → 进入既有业务错误（approval.not_found），
        // 而非 project.* ——证明授权层放行后业务正常进行。
        adminClient.post().uri("/api/approvals/999999/approve")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.errorCode").value(v ->
                        assertThat(String.valueOf(v)).doesNotContain("project."));
    }

    // ===== push：task:manage（EDITOR+）=====

    @Test
    void viewer_cannot_push_role_forbidden() {
        // PushCommand 字段以 ProjectSyncDtos 实际 record 为准（files/baseline/force/...）；viewer 在授权层即被拒。
        viewerClient.post().uri("/api/projects/1/push")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("files", Map.of(), "force", false))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
    }
}
