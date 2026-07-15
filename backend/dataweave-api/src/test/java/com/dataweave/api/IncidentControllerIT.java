package com.dataweave.api;

import java.time.LocalDateTime;
import java.util.UUID;

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

import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 069 US1 契约测试（quickstart 场景 1）：Sweeper 开单/归并/TEST·BACKFILL 排除、
 * DIAG_UNAVAILABLE 降级（未配置模型）、Controller HTTP 契约（200+$.code/$.data，401 未鉴权）。
 * 独立 H2 库（防串台），LLM 未配置故诊断走确定性降级路径，无需真外呼。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-incident-069;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@DisplayName("Incident US1 诊断闭环 HTTP 契约（069）")
class IncidentControllerIT {

    @LocalServerPort
    int port;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    IncidentSweeper sweeper;

    WebTestClient client;

    static final long TASK_A = 880101L;
    static final long TASK_B = 880102L;
    static final long TASK_TEST = 880103L;
    static final long TASK_BACKFILL = 880104L;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .defaultHeader("X-Project-Id", "1")
                .responseTimeout(java.time.Duration.ofSeconds(20))
                .build();
    }

    private void insertTaskDef(long id, String name) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO task_def (id, tenant_id, project_id, name, type, content, created_at, updated_at, deleted, version) "
                + "VALUES (?, 1, 1, ?, 'SQL', 'select 1', ?, ?, 0, 0)",
                id, name, now, now);
    }

    /** 诊断在后台线程池异步执行（无外部依赖时应近乎瞬时），轮询等待代替 sleep 固定时长。 */
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

    private UUID insertFailedInstance(long taskId, String runMode, String cronExpression) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO task_instance (id, tenant_id, project_id, task_id, task_def_name, run_mode, " +
                "  cron_expression, state, attempt, error_message, log, created_at, updated_at, deleted, version) " +
                "VALUES (?, 1, 1, ?, 'demo', ?, ?, 'FAILED', 1, 'boom', 'ERROR: something failed', ?, ?, 0, 0)",
                id, taskId, runMode, cronExpression, now, now);
        return id;
    }

    @Test
    @DisplayName("Sweeper 为 FAILED 实例自动开单，降级 DIAG_UNAVAILABLE（未配置模型）")
    void sweeperOpensIncidentAndDegradesWithoutModel() {
        insertTaskDef(TASK_A, "task-a");
        UUID instanceId = insertFailedInstance(TASK_A, "NORMAL", "0 0 * * *");

        sweeper.sweep();

        awaitTrue(() -> "DIAG_UNAVAILABLE".equals(jdbc.queryForObject(
                "SELECT state FROM incident WHERE task_def_id = ?", String.class, TASK_A)));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident WHERE task_def_id = ?", Integer.class, TASK_A);
        assertThat(count).isEqualTo(1);

        String triggerSource = jdbc.queryForObject(
                "SELECT trigger_source FROM incident WHERE task_def_id = ?", String.class, TASK_A);
        assertThat(triggerSource).isEqualTo("CRON");

        Integer firstInstanceMatch = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident WHERE task_def_id = ? AND first_instance_id = ?",
                Integer.class, TASK_A, instanceId);
        assertThat(firstInstanceMatch).isEqualTo(1);
    }

    @Test
    @DisplayName("重复扫描不重复开单/归并（幂等）；再次 sweep 后 instance_count 不变")
    void repeatedSweepIsIdempotent() {
        insertTaskDef(TASK_B, "task-b");
        insertFailedInstance(TASK_B, "NORMAL", null);

        sweeper.sweep(); // tryOpen 在 sweep() 内同步执行，返回时事故行已落库（诊断段才是异步）

        // 无新失败的情况下重复 sweep：instance_count 不应增长（linkInstance 幂等）
        sweeper.sweep();
        sweeper.sweep();

        Integer instanceCount = jdbc.queryForObject(
                "SELECT instance_count FROM incident WHERE task_def_id = ?", Integer.class, TASK_B);
        assertThat(instanceCount).isEqualTo(1);

        // 手动触发状态 MANUAL（无 cron_expression）
        String triggerSource = jdbc.queryForObject(
                "SELECT trigger_source FROM incident WHERE task_def_id = ?", String.class, TASK_B);
        assertThat(triggerSource).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("TEST/BACKFILL 运行不开单（FR-001 排除范围）")
    void testAndBackfillRunsDoNotOpenIncident() {
        insertTaskDef(TASK_TEST, "task-test");
        insertTaskDef(TASK_BACKFILL, "task-backfill");
        insertFailedInstance(TASK_TEST, "TEST", null);
        insertFailedInstance(TASK_BACKFILL, "BACKFILL", null);

        sweeper.sweep();

        Integer countTest = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident WHERE task_def_id = ?", Integer.class, TASK_TEST);
        Integer countBackfill = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident WHERE task_def_id = ?", Integer.class, TASK_BACKFILL);
        assertThat(countTest).isZero();
        assertThat(countBackfill).isZero();
    }

    @Test
    @DisplayName("GET /api/incidents 未鉴权 401；鉴权后 200+$.code=0")
    void listRequiresAuth() {
        WebTestClient noAuth = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        noAuth.get().uri("/api/incidents").exchange().expectStatus().isUnauthorized();

        client.get().uri("/api/incidents")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items").isArray();
    }

    @Test
    @DisplayName("GET /api/incidents/{id} 与 /messages 契约：详情含诊断降级消息")
    void detailAndMessagesContract() {
        insertTaskDef(880199L, "task-detail");
        insertFailedInstance(880199L, "NORMAL", null);
        sweeper.sweep(); // 事故行同步落库

        awaitTrue(() -> "DIAG_UNAVAILABLE".equals(jdbc.queryForObject(
                "SELECT state FROM incident WHERE task_def_id = ?", String.class, 880199L)));

        String idStr = jdbc.queryForObject(
                "SELECT id FROM incident WHERE task_def_id = ?", String.class, 880199L);

        client.get().uri("/api/incidents/{id}", idStr)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.incident.state").isEqualTo("DIAG_UNAVAILABLE")
                .jsonPath("$.data.messageCount").isNumber();

        client.get().uri("/api/incidents/{id}/messages?afterSeq=0", idStr)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[0].kind").isEqualTo("SYSTEM");
    }
}
