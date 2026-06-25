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
 * unified-data-table 流 B：任务定义筛选 + 数据新鲜度聚合端点全栈测试（H2）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class TaskAndFreshnessEndpointTest {

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtUtil jwtUtil;

    WebTestClient client;

    static UUID instUuid(int seq) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seq));
    }

    void insertTask(long id, String name, String type, String status, int frozen,
                    Long ownerId, Long datasourceId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO task_def (id, tenant_id, project_id, name, type, content, status, "
                        + "frozen, owner_id, datasource_id, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, ?, 'SELECT 1', ?, ?, ?, ?, ?, ?, 0, 0)",
                id, name, type, status, frozen, ownerId, datasourceId, now, now);
    }

    void insertInstance(UUID id, long taskId, String state, LocalDateTime finishedAt) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO task_instance (id, tenant_id, project_id, task_id, run_mode, state, "
                        + "biz_date, started_at, finished_at, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, 'NORMAL', ?, '2026-06-20', ?, ?, ?, ?, 0, 0)",
                id, taskId, state, finishedAt, finishedAt, now, now);
    }

    @BeforeEach
    void setUp() {
        // 清理上次测试残留的流 B 测试数据（ID 范围 880000–889999）
        jdbc.update("DELETE FROM task_instance WHERE task_id BETWEEN 880000 AND 889999");
        jdbc.update("DELETE FROM task_def WHERE id BETWEEN 880000 AND 889999");
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
    }

    // ─── TaskController: 新筛选维度 ──────────────────────────

    @Test
    void tasks_按ownerId_me筛选() {
        // JwtTestSupport.USER_ID = 1L → ownerId=me 解析为 1
        insertTask(880001L, "zzownerme", "SQL", "DRAFT", 0, 1L, null);
        insertTask(880002L, "zzownerother", "SQL", "DRAFT", 0, 99L, null);

        client.get().uri(b -> b.path("/api/tasks")
                        .queryParam("keyword", "zzowner")
                        .queryParam("ownerId", "me")
                        .queryParam("page", "0").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.content.length()").isEqualTo(1)
                .jsonPath("$.data.content[0].name").isEqualTo("zzownerme");
    }

    @Test
    void tasks_按frozen筛选() {
        insertTask(880011L, "zzfrozenyes", "SQL", "DRAFT", 1, null, null);
        insertTask(880012L, "zzfrozenno", "SQL", "DRAFT", 0, null, null);

        client.get().uri(b -> b.path("/api/tasks")
                        .queryParam("keyword", "zzfrozen")
                        .queryParam("frozen", "1")
                        .queryParam("page", "0").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.content.length()").isEqualTo(1)
                .jsonPath("$.data.content[0].name").isEqualTo("zzfrozenyes");
    }

    @Test
    void tasks_按datasourceId筛选() {
        insertTask(880021L, "zzdsmatch", "SQL", "DRAFT", 0, null, 42L);
        insertTask(880022L, "zzdsmatchother", "SQL", "DRAFT", 0, null, 99L);

        client.get().uri(b -> b.path("/api/tasks")
                        .queryParam("keyword", "zzdsm")
                        .queryParam("datasourceId", "42")
                        .queryParam("page", "0").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.content.length()").isEqualTo(1)
                .jsonPath("$.data.content[0].name").isEqualTo("zzdsmatch");
    }

    // ─── FreshnessController ─────────────────────────────────

    @Test
    void freshness_默认worstFirst_NEVER排在最前() {
        insertTask(880101L, "zzfrnever", "SQL", "ONLINE", 0, null, null);
        insertTask(880102L, "zzfrfresh", "SQL", "ONLINE", 0, null, null);
        // 1 小时前成功 → FRESH
        insertInstance(instUuid(880102), 880102L, "SUCCESS",
                LocalDateTime.now().minusHours(1));

        client.get().uri(b -> b.path("/api/freshness")
                        .queryParam("taskName", "zzfr")
                        .queryParam("page", "0").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(2)
                .jsonPath("$.data.items[0].name").isEqualTo("zzfrnever")
                .jsonPath("$.data.items[0].tier").isEqualTo("NEVER")
                .jsonPath("$.data.items[0].lastSuccessAt").doesNotExist()
                .jsonPath("$.data.items[1].name").isEqualTo("zzfrfresh")
                .jsonPath("$.data.items[1].tier").isEqualTo("FRESH");
    }

    @Test
    void freshness_按tier_NEVER筛选() {
        insertTask(880111L, "zzfrneveronly", "SQL", "ONLINE", 0, null, null);
        insertTask(880112L, "zzfrfreshonly", "SQL", "ONLINE", 0, null, null);
        insertInstance(instUuid(880112), 880112L, "SUCCESS",
                LocalDateTime.now().minusHours(1));

        client.get().uri(b -> b.path("/api/freshness")
                        .queryParam("taskName", "zzfr")
                        .queryParam("tiers", "NEVER")
                        .queryParam("page", "0").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].name").isEqualTo("zzfrneveronly")
                .jsonPath("$.data.items[0].tier").isEqualTo("NEVER");
    }

    @Test
    void freshness_按tier_STALE筛选() {
        insertTask(880121L, "zzfrstaletask", "SQL", "ONLINE", 0, null, null);
        insertTask(880122L, "zzfrfreshtask", "SQL", "ONLINE", 0, null, null);
        // 48 小时前成功 → STALE（>24h）
        insertInstance(instUuid(880121), 880121L, "SUCCESS",
                LocalDateTime.now().minusHours(48));
        // 1 小时前成功 → FRESH
        insertInstance(instUuid(880122), 880122L, "SUCCESS",
                LocalDateTime.now().minusHours(1));

        client.get().uri(b -> b.path("/api/freshness")
                        .queryParam("taskName", "zzfr")
                        .queryParam("tiers", "STALE")
                        .queryParam("page", "0").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].name").isEqualTo("zzfrstaletask")
                .jsonPath("$.data.items[0].tier").isEqualTo("STALE");
    }

    @Test
    void freshness_最陈旧优先排序() {
        insertTask(880131L, "zzfroldest", "SQL", "ONLINE", 0, null, null);
        insertTask(880132L, "zzfrnewer", "SQL", "ONLINE", 0, null, null);
        // 48 小时前成功 → STALE 且最陈旧
        insertInstance(instUuid(880131), 880131L, "SUCCESS",
                LocalDateTime.now().minusHours(48));
        // 12 小时前成功 → AGING
        insertInstance(instUuid(880132), 880132L, "SUCCESS",
                LocalDateTime.now().minusHours(12));

        client.get().uri(b -> b.path("/api/freshness")
                        .queryParam("taskName", "zzfr")
                        .queryParam("sort", "worst_first")
                        .queryParam("page", "0").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(2)
                // worst_first: STALE 排在 AGING 前面
                .jsonPath("$.data.items[0].name").isEqualTo("zzfroldest")
                .jsonPath("$.data.items[0].tier").isEqualTo("STALE")
                .jsonPath("$.data.items[1].name").isEqualTo("zzfrnewer")
                .jsonPath("$.data.items[1].tier").isEqualTo("AGING");
    }

    @Test
    void freshness_失败实例不影响新鲜度() {
        insertTask(880141L, "zzfrfailonly", "SQL", "ONLINE", 0, null, null);
        // 仅有 FAILED 实例 → 视为 NEVER
        insertInstance(instUuid(880141), 880141L, "FAILED",
                LocalDateTime.now().minusHours(1));

        client.get().uri(b -> b.path("/api/freshness")
                        .queryParam("taskName", "zzfrfail")
                        .queryParam("page", "0").queryParam("size", "20").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].tier").isEqualTo("NEVER")
                .jsonPath("$.data.items[0].lastSuccessAt").doesNotExist();
    }
}
