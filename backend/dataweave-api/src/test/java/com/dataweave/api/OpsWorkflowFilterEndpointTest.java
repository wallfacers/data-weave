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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * unified-data-table 流 A：周期/手动任务流筛选分页 + 补数据筛选分页端点全栈测试（真 H2 + schema）。
 *
 * <p>覆盖：GET /periodic-workflows（名称/hasDraftChange/recentResult 筛选 + Page 信封）、
 * GET /manual-workflows（名称筛选）、GET /backfill（state 多选/targetName 筛选 + Page 信封）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class OpsWorkflowFilterEndpointTest {

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtUtil jwtUtil;

    WebTestClient client;

    static UUID uuid(int seq) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seq));
    }

    void insertWorkflow(long id, String name, String scheduleType, String status, int hasDraft, String cron) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO workflow_def (id, tenant_id, project_id, name, schedule_type, status, "
                        + "has_draft_change, cron, current_version_no, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, ?, ?, ?, ?, 1, ?, ?, 0, 0)",
                id, name, scheduleType, status, hasDraft, cron, now, now);
    }

    void insertWorkflowInstance(UUID id, long workflowId, String state) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, biz_date, "
                        + "created_at, updated_at, deleted, version) VALUES (?, 1, 1, ?, ?, ?, ?, ?, 0, 0)",
                id, workflowId, state, "2026-06-20", now, now);
    }

    void insertBackfillRun(UUID id, String targetType, long targetId, String targetName,
                           String dateStart, String dateEnd, String state) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO backfill_run (id, tenant_id, project_id, target_type, target_id, target_name, "
                        + "date_start, date_end, state, total, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, ?, ?, ?, ?, ?, 0, ?, ?, 0, 0)",
                id, targetType, targetId, targetName, dateStart, dateEnd, state, now, now);
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
    }

    // ─── 周期任务流：名称筛选 + Page 信封 ──────────────────────

    @Test
    void periodicWorkflows_按名称筛选_返回Page信封() {
        insertWorkflow(990101L, "uniqkw_cron_alpha", "CRON", "ONLINE", 0, "0 0 2 * * ?");
        insertWorkflow(990102L, "uniqkw_cron_beta", "CRON", "ONLINE", 0, "0 0 3 * * ?");

        client.get().uri(b -> b.path("/api/ops/periodic-workflows")
                        .queryParam("keyword", "uniqkw_cron_alpha")
                        .queryParam("page", "1").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].name").isEqualTo("uniqkw_cron_alpha")
                .jsonPath("$.data.total").isEqualTo(1);
    }

    @Test
    void periodicWorkflows_按hasDraftChange筛选() {
        insertWorkflow(990201L, "draftkw_dirty", "CRON", "ONLINE", 1, "0 0 2 * * ?");
        insertWorkflow(990202L, "draftkw_clean", "CRON", "ONLINE", 0, "0 0 2 * * ?");

        client.get().uri(b -> b.path("/api/ops/periodic-workflows")
                        .queryParam("keyword", "draftkw_")
                        .queryParam("hasDraftChange", "1")
                        .queryParam("page", "1").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].name").isEqualTo("draftkw_dirty")
                .jsonPath("$.data.items[0].hasDraftChange").isEqualTo(1);
    }

    @Test
    void periodicWorkflows_按最近触发结果筛选() {
        insertWorkflow(990301L, "recentkw_failed", "CRON", "ONLINE", 0, "0 0 2 * * ?");
        insertWorkflow(990302L, "recentkw_never", "CRON", "ONLINE", 0, "0 0 2 * * ?");
        insertWorkflowInstance(uuid(990301), 990301L, "FAILED");

        // recentResult=FAILED → 只剩有失败实例的那个
        client.get().uri(b -> b.path("/api/ops/periodic-workflows")
                        .queryParam("keyword", "recentkw_")
                        .queryParam("recentResult", "FAILED")
                        .queryParam("page", "1").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].name").isEqualTo("recentkw_failed")
                .jsonPath("$.data.items[0].recentTriggerResult").isEqualTo("FAILED");

        // recentResult=NEVER → 只剩从未触发的那个
        client.get().uri(b -> b.path("/api/ops/periodic-workflows")
                        .queryParam("keyword", "recentkw_")
                        .queryParam("recentResult", "NEVER")
                        .queryParam("page", "1").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].name").isEqualTo("recentkw_never");
    }

    @Test
    void periodicWorkflows_暴露衍生列() {
        insertWorkflow(990401L, "colskw_one", "CRON", "ONLINE", 1, "0 0 2 * * ?");

        client.get().uri(b -> b.path("/api/ops/periodic-workflows")
                        .queryParam("keyword", "colskw_one")
                        .queryParam("page", "1").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items[0].currentVersionNo").isEqualTo(1)
                .jsonPath("$.data.items[0].hasDraftChange").isEqualTo(1);
    }

    // ─── 手动任务流：名称筛选 ──────────────────────

    @Test
    void manualWorkflows_按名称筛选() {
        insertWorkflow(990501L, "manualkw_x", "MANUAL", "ONLINE", 0, null);
        insertWorkflow(990502L, "manualkw_y", "MANUAL", "ONLINE", 0, null);

        client.get().uri(b -> b.path("/api/ops/manual-workflows")
                        .queryParam("keyword", "manualkw_x")
                        .queryParam("page", "1").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].name").isEqualTo("manualkw_x");
    }

    @Test
    void manualWorkflows_不串入CRON() {
        insertWorkflow(990601L, "mixkw_manual", "MANUAL", "ONLINE", 0, null);
        insertWorkflow(990602L, "mixkw_cron", "CRON", "ONLINE", 0, "0 0 2 * * ?");

        client.get().uri(b -> b.path("/api/ops/manual-workflows")
                        .queryParam("keyword", "mixkw_")
                        .queryParam("page", "1").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].name").isEqualTo("mixkw_manual");
    }

    // ─── 补数据：state 多选 + targetName 筛选 + Page 信封 ──────────────────────

    @Test
    void backfillList_按state多选筛选() {
        insertBackfillRun(uuid(991001), "task", 1L, "bffilt_a", "2026-06-01", "2026-06-03", "PARTIAL");
        insertBackfillRun(uuid(991002), "task", 2L, "bffilt_b", "2026-06-01", "2026-06-03", "SUCCESS");
        insertBackfillRun(uuid(991003), "task", 3L, "bffilt_c", "2026-06-01", "2026-06-03", "RUNNING");

        // state=PARTIAL,RUNNING → 两条（CSV 多选）
        client.get().uri(b -> b.path("/api/ops/backfill")
                        .queryParam("state", "PARTIAL,RUNNING")
                        .queryParam("targetName", "bffilt_")
                        .queryParam("page", "1").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(2)
                .jsonPath("$.data.total").isEqualTo(2);
    }

    @Test
    void backfillList_按targetName筛选_返回Page信封() {
        insertBackfillRun(uuid(991101), "task", 11L, "tgtname_uniq", "2026-06-01", "2026-06-03", "SUCCESS");

        client.get().uri(b -> b.path("/api/ops/backfill")
                        .queryParam("targetName", "tgtname_uniq")
                        .queryParam("page", "1").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].targetName").isEqualTo("tgtname_uniq")
                .jsonPath("$.data.total").isEqualTo(1);
    }

    @Test
    void backfillList_按业务日期区间筛选() {
        insertBackfillRun(uuid(991201), "task", 21L, "datekw_in", "2026-07-10", "2026-07-12", "SUCCESS");
        insertBackfillRun(uuid(991202), "task", 22L, "datekw_out", "2026-08-01", "2026-08-03", "SUCCESS");

        // bizDate 区间 [2026-07-01, 2026-07-31] 只与 in 重叠
        client.get().uri(b -> b.path("/api/ops/backfill")
                        .queryParam("targetName", "datekw_")
                        .queryParam("bizDateFrom", "2026-07-01")
                        .queryParam("bizDateTo", "2026-07-31")
                        .queryParam("page", "1").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].targetName").isEqualTo("datekw_in");
    }

    // ─── 鉴权 ──────────────────────

    @Test
    void 未鉴权返回401() {
        WebTestClient noAuth = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        noAuth.get().uri("/api/ops/periodic-workflows?keyword=x")
                .exchange().expectStatus().isUnauthorized();
    }
}
