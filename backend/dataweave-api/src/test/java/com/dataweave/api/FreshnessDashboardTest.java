package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import com.dataweave.master.application.FreshnessService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freshness dashboard 端点 + 快照功能全栈测试（H2）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class FreshnessDashboardTest {

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    FreshnessService freshnessService;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(h -> h.set("Authorization", JwtTestSupport.bearer(jwtUtil)))
                .build();
    }

    void insertTask(long id, String name) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO task_def (id, tenant_id, project_id, name, type, content, status, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, 'SHELL', 'SELECT 1', 'ONLINE', ?, ?, 0, 0)",
                id, name, now, now);
    }

    void insertInstance(UUID id, long taskId, String state, LocalDateTime finishedAt) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO task_instance (id, tenant_id, project_id, task_id, run_mode, state, biz_date, started_at, finished_at, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, 'NORMAL', ?, '2026-07-03', ?, ?, ?, ?, 0, 0)",
                id, taskId, state, now, finishedAt, now, now);
    }

    @Test
    void dashboardReturnsSummary() {
        insertTask(1001, "dashboard-test-task");
        insertInstance(UUID.randomUUID(), 1001, "SUCCESS", LocalDateTime.now().minusHours(3));

        client.get()
                .uri("/api/freshness/dashboard?projectId=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.summary").exists()
                .jsonPath("$.data.snapshotDate").isNotEmpty();
    }

    @Test
    void queryReturnsRows() {
        // 覆盖列表端点 GET /api/freshness（含 trend 标量子查询），防 LATERAL 等 H2 兼容回归
        insertTask(1004, "query-test-task");
        insertInstance(UUID.randomUUID(), 1004, "SUCCESS", LocalDateTime.now().minusHours(2));

        client.get()
                .uri("/api/freshness?projectId=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items").exists()
                .jsonPath("$.data.total").isNotEmpty();
    }

    @Test
    void snapshotIsIdempotent() {
        insertTask(1002, "snapshot-idempotent-task");
        insertInstance(UUID.randomUUID(), 1002, "SUCCESS", LocalDateTime.now().minusHours(1));

        // 两次调用不应报错（ON CONFLICT DO NOTHING 幂等）
        freshnessService.takeSnapshot(1L, 1L);
        freshnessService.takeSnapshot(1L, 1L);

        // 今日项目级聚合快照唯一（幂等不重复）
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM freshness_daily_snapshot WHERE project_id = 1 AND snapshot_date = CURRENT_DATE",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void trendIsNullWithoutYesterdaySnapshot() {
        insertTask(1003, "trend-null-task");
        insertInstance(UUID.randomUUID(), 1003, "SUCCESS", LocalDateTime.now().minusHours(5));

        client.get()
                .uri("/api/freshness/dashboard?projectId=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.trend").doesNotExist();
    }

    @Test
    void cleanupRemovesOldData() {
        // 插入一条超过 90 天的旧快照
        jdbc.update(
                "INSERT INTO freshness_task_daily (tenant_id, project_id, task_id, snapshot_date, tier, age_hours) "
                        + "VALUES (1, 1, 9999, DATEADD('DAY', -100, CURRENT_DATE), 'FRESH', 1)");
        freshnessService.cleanupOldSnapshots();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM freshness_task_daily WHERE task_id = 9999", Integer.class);
        assertThat(count).isZero();
    }
}
