package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 036 收口 T045b：alert 的 by-id 端点（rules/channels 的 get·update·delete）必须校验实体的
 * 项目归属。用户 1 同为项目 A、B 成员（都能过 ProjectScope 成员校验），但资源属于 A ——
 * 以 B 上下文按 id 读/改/删 A 的资源须被 project.forbidden 拦截，且资源不变。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class AlertCrossProjectGuardTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbc;

    private static final long PROJ_A = 9101L;
    private static final long PROJ_B = 9102L;

    private WebTestClient asA;
    private WebTestClient asB;

    @BeforeEach
    void setUp() {
        String bearer = JwtTestSupport.bearer(jwtUtil);
        this.asA = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", bearer)
                .defaultHeader("X-Project-Id", String.valueOf(PROJ_A))
                .build();
        this.asB = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", bearer)
                .defaultHeader("X-Project-Id", String.valueOf(PROJ_B))
                .build();

        jdbc.update("DELETE FROM alert_rule WHERE project_id IN (?, ?)", PROJ_A, PROJ_B);
        jdbc.update("DELETE FROM alert_channel WHERE project_id IN (?, ?)", PROJ_A, PROJ_B);
        ensureMember(PROJ_A);
        ensureMember(PROJ_B);
    }

    private void ensureMember(long projectId) {
        jdbc.update("INSERT INTO projects (id, tenant_id, code, name, owner_id, status, created_by, updated_by, created_at, updated_at, deleted, version) "
                + "SELECT ?, 1, ?, ?, 1, 'ACTIVE', 1, 1, NOW(), NOW(), 0, 0 "
                + "WHERE NOT EXISTS (SELECT 1 FROM projects WHERE id=?)",
                projectId, "proj-" + projectId, "P" + projectId, projectId);
        jdbc.update("INSERT INTO project_member (id, tenant_id, project_id, user_id, role_id, created_by, updated_by, created_at, updated_at, deleted, version) "
                + "SELECT ?, 1, ?, 1, 1, 1, 1, NOW(), NOW(), 0, 0 "
                + "WHERE NOT EXISTS (SELECT 1 FROM project_member WHERE tenant_id=1 AND project_id=? AND user_id=1 AND deleted=0)",
                projectId * 100, projectId, projectId);
    }

    private long insertRuleA(String name) {
        jdbc.update("INSERT INTO alert_rule (tenant_id, project_id, name, signal_source, eval_mode, severity) "
                + "VALUES (1, ?, ?, 'METRIC', 'THRESHOLD', 'WARNING')", PROJ_A, name);
        return jdbc.queryForObject("SELECT id FROM alert_rule WHERE project_id=? AND name=?", Long.class, PROJ_A, name);
    }

    private long insertChannelA(String name) {
        jdbc.update("INSERT INTO alert_channel (tenant_id, project_id, name, type) VALUES (1, ?, ?, 'WEBHOOK')", PROJ_A, name);
        return jdbc.queryForObject("SELECT id FROM alert_channel WHERE project_id=? AND name=?", Long.class, PROJ_A, name);
    }

    @Test
    void getRule_sameProject_ok() {
        long id = insertRuleA("r-ok");
        asA.get().uri("/api/alert/rules/" + id).exchange()
                .expectBody().jsonPath("$.code").isEqualTo(0);
    }

    @Test
    void getRule_crossProject_forbidden() {
        long id = insertRuleA("r-get");
        asB.get().uri("/api/alert/rules/" + id).exchange()
                .expectBody().jsonPath("$.errorCode").isEqualTo("project.forbidden");
    }

    @Test
    void updateRule_crossProject_forbidden_andUnchanged() {
        long id = insertRuleA("r-upd");
        asB.patch().uri("/api/alert/rules/" + id).bodyValue(java.util.Map.of("name", "hacked")).exchange()
                .expectBody().jsonPath("$.errorCode").isEqualTo("project.forbidden");
        String name = jdbc.queryForObject("SELECT name FROM alert_rule WHERE id=?", String.class, id);
        assertThat(name).isEqualTo("r-upd");
    }

    @Test
    void deleteRule_crossProject_forbidden_andNotDeleted() {
        long id = insertRuleA("r-del");
        asB.delete().uri("/api/alert/rules/" + id).exchange()
                .expectBody().jsonPath("$.errorCode").isEqualTo("project.forbidden");
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM alert_rule WHERE id=? AND deleted=0", Integer.class, id);
        assertThat(cnt).isEqualTo(1);
    }

    @Test
    void getChannel_crossProject_forbidden() {
        long id = insertChannelA("c-get");
        asB.get().uri("/api/alert/channels/" + id).exchange()
                .expectBody().jsonPath("$.errorCode").isEqualTo("project.forbidden");
    }

    @Test
    void deleteChannel_crossProject_forbidden_andNotDeleted() {
        long id = insertChannelA("c-del");
        asB.delete().uri("/api/alert/channels/" + id).exchange()
                .expectBody().jsonPath("$.errorCode").isEqualTo("project.forbidden");
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM alert_channel WHERE id=?", Integer.class, id);
        assertThat(cnt).isEqualTo(1);
    }
}
