package com.dataweave.api;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.dataweave.api.infrastructure.JwtUtil;
import com.dataweave.master.application.incident.IncidentSweeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 070 监督席对话体验：发言身份服务端认定（body.actor 忽略、actor_name=displayName 落库）与
 * 打断端点经写闸门 L0（直执行+留痕，绝不 PENDING_APPROVAL）。独立 H2 库防串台。
 * JwtTestSupport 身份：userId=1 / username="tester"；users 种子 id=1 display_name="管理员"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-incident-070;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@DisplayName("Incident 070 对话身份+打断 HTTP 契约")
class IncidentChat070IT {

    @LocalServerPort
    int port;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    IncidentSweeper sweeper;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .defaultHeader("X-Project-Id", "1")
                .responseTimeout(java.time.Duration.ofSeconds(20))
                .build();
    }

    private void awaitTrue(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("condition not met within timeout");
    }

    private UUID openIncident(long taskId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO task_def (id, tenant_id, project_id, name, type, content, created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, 1, ?, 'SQL', 'select 1', ?, ?, 0, 0)", taskId, "t-" + taskId, now, now);
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, task_id, task_def_name, run_mode, cron_expression, "
                + "state, attempt, error_message, log, created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, 1, ?, 'demo', 'NORMAL', null, 'FAILED', 1, 'boom', 'ERROR', ?, ?, 0, 0)",
                UUID.randomUUID(), taskId, now, now);
        sweeper.sweep();
        awaitTrue(() -> jdbc.queryForObject("SELECT COUNT(*) FROM incident WHERE task_def_id = ?", Integer.class, taskId) == 1);
        return UUID.fromString(jdbc.queryForObject("SELECT id FROM incident WHERE task_def_id = ?", String.class, taskId));
    }

    /** 直插一条 ops_enabled=1 的 agent 配置（免鉴权网关，无需真外呼——respond 在后台降级即可）。 */
    private void enableOps() {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO lineage_agent_config (tenant_id, protocol, base_url, model, api_key_enc, "
                + "enabled, ops_enabled, timeout_ms, rate_limit_per_min, max_columns, created_by, updated_by, "
                + "created_at, updated_at, deleted, version) "
                + "VALUES (1, 'OPENAI', 'http://localhost:1/v1', 'gpt', NULL, 1, 1, 30000, 60, 2000, 1, 1, ?, ?, 0, 0)",
                now, now);
    }

    record ChatBody(String text, String actor) {
    }

    @Test
    @DisplayName("chat 发言身份服务端认定：body.actor 被忽略，落库 actor=登录名、actor_name=显示名")
    void chatIdentityIsServerAuthenticated() {
        UUID incidentId = openIncident(880701L);
        enableOps();

        // body 自报 actor="hacker" 应被忽略；服务端用 JWT 身份（username=tester）+ 显示名（管理员）。
        client.post().uri("/api/incidents/{id}/chat", incidentId)
                .bodyValue(new ChatBody("帮我看看根因", "hacker"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.kind").isEqualTo("HUMAN_SAY")
                .jsonPath("$.data.actor").isEqualTo("tester")
                .jsonPath("$.data.actorName").isEqualTo("管理员");

        // 落库校验：actor 不等于 body 自报的 hacker
        String actor = jdbc.queryForObject(
                "SELECT actor FROM incident_message WHERE incident_id = ? AND kind = 'HUMAN_SAY'",
                String.class, incidentId);
        assertThat(actor).isEqualTo("tester").isNotEqualTo("hacker");
    }

    @Test
    @DisplayName("打断端点无在途轮次时幂等 cancelled=false，且经闸门 L0 直执行留痕（非 PENDING_APPROVAL）")
    void cancelGoesThroughGateAtL0() {
        UUID incidentId = openIncident(880702L);

        client.post().uri("/api/incidents/{id}/agent/cancel", incidentId)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.cancelled").isEqualTo(false); // 无在途轮次

        // 审计断言：agent_action 有 INCIDENT_AGENT_CANCEL 记录，L0、approval_status=NONE（非 PENDING）
        Integer audit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_action WHERE action_type = 'INCIDENT_AGENT_CANCEL' "
                + "AND policy_level = 'L0' AND approval_status = 'NONE'", Integer.class);
        assertThat(audit).isGreaterThanOrEqualTo(1);

        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_action WHERE action_type = 'INCIDENT_AGENT_CANCEL' "
                + "AND approval_status = 'PENDING'", Integer.class);
        assertThat(pending).isZero();
    }
}
