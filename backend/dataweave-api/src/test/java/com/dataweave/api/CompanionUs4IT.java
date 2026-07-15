package com.dataweave.api;

import java.time.Duration;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 071 US4 例程治理后端契约（T029）：四领域例程列表 / PATCH 启停+调频+scope 语义 / 执行历史 / 项目隔离。
 * 独立 H2 库；禁调度器（PATCH/trigger 直连不受影响，trigger 也在本 IT 验）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-companion-us4-071;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "companion.patrol.enabled=false"
})
@DisplayName("Companion US4 例程治理 契约（071）")
class CompanionUs4IT {

    @LocalServerPort
    int port;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    JdbcTemplate jdbc;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .defaultHeader("X-Project-Id", "1")
                .responseTimeout(Duration.ofSeconds(20))
                .build();
        // 种隔离用项目 2 + user 1 成员资格（ADMIN role=1）
        jdbc.update("MERGE INTO projects (id, tenant_id, code, name, owner_id, status, created_by, updated_by, created_at, updated_at, deleted, version) " +
                "KEY(id) VALUES (2, 1, 'iso', '隔离项目', 1, 'ACTIVE', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)");
        jdbc.update("MERGE INTO project_member (id, tenant_id, project_id, user_id, role_id, created_by, updated_by, created_at, updated_at, deleted, version) " +
                "KEY(id) VALUES (200, 1, 2, 1, 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)");
        // 重置例程 1（TASK_FAILURE）到 seed 态，保证测试幂等
        jdbc.update("UPDATE patrol_routine SET enabled = 1, cron_expression = '0 */15 * * * *', scope_json = NULL, version = version WHERE id = 1");
    }

    @Test
    @DisplayName("GET /routines 返回四领域 seed 例程")
    void routines_listFourDomains() {
        client.get().uri("/api/companion/routines").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.length()").isEqualTo(4)
                .jsonPath("$.data[0].domain").exists()
                .jsonPath("$.data[?(@.domain=='TASK_FAILURE')]").exists();
    }

    @Test
    @DisplayName("PATCH 启停 + 改 cron 生效")
    void patch_updatesEnabledAndCron() {
        client.patch().uri("/api/companion/routines/{id}", 1)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"enabled\":false,\"cronExpression\":\"0 0 3 * * *\"}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.enabled").isEqualTo(false)
                .jsonPath("$.data.cronExpression").isEqualTo("0 0 3 * * *");

        // 持久化校验
        Integer enabled = jdbc.queryForObject("SELECT enabled FROM patrol_routine WHERE id = 1", Integer.class);
        org.assertj.core.api.Assertions.assertThat(enabled).isEqualTo(0);
    }

    @Test
    @DisplayName("PATCH scopeJson 显式 null 清空；字段缺失不改")
    void patch_scopeSemantics() {
        // 先设值
        client.patch().uri("/api/companion/routines/{id}", 1)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"scopeJson\":\"{\\\"dir\\\":\\\"/etl\\\"}\"}")
                .exchange().expectStatus().isOk();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT scope_json FROM patrol_routine WHERE id = 1", String.class)).contains("/etl");

        // 缺失 scopeJson → 不改（只改 enabled，scope 保留）
        client.patch().uri("/api/companion/routines/{id}", 1)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"enabled\":false}")
                .exchange().expectStatus().isOk();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT scope_json FROM patrol_routine WHERE id = 1", String.class)).contains("/etl");

        // 显式 null → 清空
        client.patch().uri("/api/companion/routines/{id}", 1)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"scopeJson\":null}")
                .exchange().expectStatus().isOk();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT scope_json FROM patrol_routine WHERE id = 1", String.class)).isNull();
    }

    @Test
    @DisplayName("PATCH 跨项目隔离：用 projectId=2 改项目1的例程 → not_found")
    void patch_projectIsolation() {
        client.patch().uri("/api/companion/routines/{id}?projectId=2", 1)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"enabled\":false}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.errorCode").isEqualTo("companion.routine_not_found");
        // 未被改（隔离生效）
        Integer enabled = jdbc.queryForObject("SELECT enabled FROM patrol_routine WHERE id = 1", Integer.class);
        org.assertj.core.api.Assertions.assertThat(enabled).isEqualTo(1);
    }

    @Test
    @DisplayName("PATCH 非法 cron → companion.cron_invalid（专用码，禁复用 domain_unknown）")
    void patch_invalidCron() {
        client.patch().uri("/api/companion/routines/{id}", 1)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"cronExpression\":\"not-a-cron\"}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.errorCode").isEqualTo("companion.cron_invalid");
    }

    @Test
    @DisplayName("GET /routines/{id}/runs 执行历史可见（trigger 后产生 run）")
    void runs_history() {
        client.post().uri("/api/companion/routines/{id}/trigger", 1).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.runId").isNumber();

        // 等异步执行落 run
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 8_000) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM patrol_run WHERE routine_id = 1", Integer.class);
            if (n != null && n > 0) break;
            sleepQuiet(200);
        }
        client.get().uri("/api/companion/routines/{id}/runs", 1).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[0].state").exists();
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
