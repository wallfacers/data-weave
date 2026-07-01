package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.AfterEach;
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
import static org.hamcrest.Matchers.hasItems;

/**
 * 036-D 项目角色授权端到端（H2，@SpringBootTest + WebTestClient + JWT）。
 *
 * <p>造 ADMIN / DEVELOPER / VIEWER 三成员（复用 seed 的 admin/analyst + 新造 viewer），
 * 断言：
 * <ul>
 *   <li>GET /api/projects/{id}/me 三角色返回的角色/权限矩阵一致（SC-004）；</li>
 *   <li>POST /api/projects/{id}/members：VIEWER/DEVELOPER 越权写被拒
 *       （{@code project.role.forbidden}，HTTP body code=403），ADMIN 放行（FR-042）；</li>
 *   <li>非成员写操作返回 {@code project.forbidden}。</li>
 * </ul>
 * 复用 seed：project 1（demo）、roles 1/2/3（ADMIN/DEVELOPER/VIEWER）、role_permission 矩阵
 * （ADMIN 5 权、DEVELOPER 4 权、VIEWER 无）、project_member(user1→ADMIN, user2→DEVELOPER)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class ProjectRoleAuthzTest {

    private static final long TENANT = 1L;
    private static final long PROJECT = 1L;

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    JdbcTemplate jdbc;

    WebTestClient adminClient;   // user 1 = ADMIN
    WebTestClient devClient;     // user 2 = DEVELOPER
    WebTestClient viewerClient;  // user 3 = VIEWER（@BeforeEach 造）
    WebTestClient outsiderClient;// user 4 = 非项目成员（@BeforeEach 造）

    @BeforeEach
    void setUp() {
        // 造 user3（VIEWER）+ user4（非成员）；幂等（先删后插）。
        jdbc.update("DELETE FROM project_member WHERE user_id IN (3, 4) AND project_id = ?", PROJECT);
        jdbc.update("DELETE FROM users WHERE id IN (3, 4)");
        jdbc.update("INSERT INTO users (id, tenant_id, username, password_hash, display_name, status, "
                + "created_by, updated_by, created_at, updated_at, deleted, version) VALUES "
                + "(3, 1, 'viewer', '{plain}viewer', 'viewer', 'ACTIVE', 1, 1, "
                + "TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0),"
                + "(4, 1, 'outsider', '{plain}outsider', 'outsider', 'ACTIVE', 1, 1, "
                + "TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00', 0, 0)");
        // user3 = project1 的 VIEWER（role_id=3）；user4 不加成员行 → 非成员。
        jdbc.update("INSERT INTO project_member (id, tenant_id, project_id, user_id, role_id, "
                + "created_by, updated_by, created_at, updated_at, deleted, version) VALUES "
                + "(100, 1, ?, 3, 3, 1, 1, TIMESTAMP '2026-06-01 00:00:00', "
                + "TIMESTAMP '2026-06-01 00:00:00', 0, 0)", PROJECT);
        // 推进 H2 IDENTITY 计数器：固定 id=100 插入后计数器停在 100，后续 admin_can_add_member
        // 的 repository.save（自增）会再生 100 撞主键；RESTART 到高位避免冲突（仅 H2 测试环境）。
        jdbc.execute("ALTER TABLE project_member ALTER COLUMN id RESTART WITH 100000");

        adminClient = clientFor(1L, "admin");
        devClient = clientFor(2L, "developer");
        viewerClient = clientFor(3L, "viewer");
        outsiderClient = clientFor(4L, "outsider");
    }

    @AfterEach
    void tearDown() {
        // 清理 admin_addMember_ok 可能插入的临时成员行（user 5+）
        jdbc.update("DELETE FROM project_member WHERE project_id = ? AND user_id >= 5", PROJECT);
    }

    private WebTestClient clientFor(long userId, String name) {
        String bearer = "Bearer " + jwtUtil.generate(userId, TENANT, name, List.of("ADMIN"));
        // 带 X-Project-Id 让 JwtAuthFilter 解析到 projectId（非 null）——地基 filter 的 exchange
        // attributes 是 ConcurrentHashMap，put(null) 会 NPE（已回报收尾方修 filter:140）。
        // me / 授权用 path id 解析角色，语义上不依赖此头值。
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", bearer)
                .defaultHeader("X-Project-Id", String.valueOf(PROJECT))
                .build();
    }

    // ===== GET /api/projects/{id}/me：角色权限矩阵（SC-004）=====

    @Test
    void admin_me_returns_admin_role_and_five_permissions() {
        adminClient.get().uri("/api/projects/{id}/me", PROJECT)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.member").isEqualTo(true)
                .jsonPath("$.data.roleCode").isEqualTo("ADMIN")
                .jsonPath("$.data.permissions.length()").isEqualTo(5)
                .jsonPath("$.data.permissions").value(hasItems(
                        "task:manage", "workflow:manage", "metric:manage",
                        "datasource:manage", "project:manage"));
    }

    @Test
    void developer_me_returns_developer_role_without_project_manage() {
        devClient.get().uri("/api/projects/{id}/me", PROJECT)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.roleCode").isEqualTo("DEVELOPER")
                .jsonPath("$.data.permissions.length()").isEqualTo(4)
                .jsonPath("$.data.permissions").value(hasItems(
                        "task:manage", "workflow:manage", "metric:manage", "datasource:manage"));
    }

    @Test
    void viewer_me_returns_viewer_role_and_no_permissions() {
        viewerClient.get().uri("/api/projects/{id}/me", PROJECT)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.roleCode").isEqualTo("VIEWER")
                .jsonPath("$.data.permissions.length()").isEqualTo(0);
    }

    @Test
    void outsider_me_reports_non_member() {
        outsiderClient.get().uri("/api/projects/{id}/me", PROJECT)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.member").isEqualTo(false)
                .jsonPath("$.data.roleCode").isEmpty()
                .jsonPath("$.data.permissions.length()").isEqualTo(0);
    }

    // ===== POST /api/projects/{id}/members：越权写被拒（FR-042）=====

    @Test
    void viewer_cannot_add_member_role_forbidden() {
        viewerClient.post().uri("/api/projects/{id}/members", PROJECT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userId", 5, "roleId", 3))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
        // 确认未写入
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_member WHERE project_id = ? AND user_id = 5",
                Integer.class, PROJECT);
        assertThat(cnt).isEqualTo(0);
    }

    @Test
    void developer_cannot_add_member_role_forbidden() {
        devClient.post().uri("/api/projects/{id}/members", PROJECT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userId", 6, "roleId", 3))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.role.forbidden");
    }

    @Test
    void outsider_cannot_add_member_project_forbidden() {
        // 非成员 → project.forbidden（先于角色校验）
        outsiderClient.post().uri("/api/projects/{id}/members", PROJECT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userId", 7, "roleId", 3))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.forbidden");
    }

    @Test
    void admin_can_add_member() {
        adminClient.post().uri("/api/projects/{id}/members", PROJECT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userId", 8, "roleId", 3))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_member WHERE project_id = ? AND user_id = 8 AND deleted = 0",
                Integer.class, PROJECT);
        assertThat(cnt).isEqualTo(1);
    }
}
