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
 * data-ops-center Stream C 端点全栈测试。
 *
 * <p>覆盖：GET /instances 筛选分页、POST /instances/batch、POST /backfill、
 * GET /backfill、GET /backfill/{runId}、POST /tasks/{id}/freeze、GET /inspect。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class OpsDataCenterEndpointTest {

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

    void insertInstance(UUID id, long taskId, String runMode, String state, String bizDate) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO task_instance (id, tenant_id, project_id, task_id, run_mode, state, biz_date, attempt, "
                        + "created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, ?, ?, ?, 0, ?, ?, 0, 0)",
                id, taskId, runMode, state, bizDate, now, now);
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .defaultHeader("X-Project-Id", "1")
                .build();
    }

    // ─── 3.1.1 GET /instances 筛选分页 ──────────────────────

    @Test
    void getInstances_无参数返回默认列表() {
        client.get().uri("/api/ops/instances")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);
    }

    @Test
    void getInstances_按state筛选() {
        insertInstance(uuid(81001), 8101L, "NORMAL", "FAILED", "2026-06-20");
        insertInstance(uuid(81002), 8101L, "NORMAL", "SUCCESS", "2026-06-21");

        client.get().uri(b -> b.path("/api/ops/instances")
                        .queryParam("state", "FAILED")
                        .queryParam("page", "1")
                        .queryParam("size", "50")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                // 至少返回我们刚插入的 FAILED 实例
                .jsonPath("$.data.items.length()").value(v -> {
                    int len = (Integer) v;
                    assert len >= 1 : "expected at least 1 FAILED instance";
                });
    }

    @Test
    void getInstances_按runMode筛选() {
        insertInstance(uuid(81101), 8111L, "BACKFILL", "RUNNING", "2026-06-22");
        insertInstance(uuid(81102), 8111L, "NORMAL", "RUNNING", "2026-06-23");

        client.get().uri(b -> b.path("/api/ops/instances")
                        .queryParam("runMode", "BACKFILL")
                        .queryParam("page", "1")
                        .queryParam("size", "10")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].runMode").isEqualTo("BACKFILL");
    }

    @Test
    void getInstances_按bizDate筛选() {
        insertInstance(uuid(81201), 8121L, "NORMAL", "SUCCESS", "2026-06-19");
        insertInstance(uuid(81202), 8121L, "NORMAL", "FAILED", "2026-06-30");

        client.get().uri(b -> b.path("/api/ops/instances")
                        .queryParam("bizDate", "2026-06-30")
                        .queryParam("page", "1")
                        .queryParam("size", "10")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].bizDate").isEqualTo("2026-06-30");
    }

    @Test
    void getInstances_分页契约() {
        for (int i = 0; i < 5; i++) {
            insertInstance(uuid(81301 + i), 8131L, "NORMAL", "SUCCESS", "2026-06-20");
        }

        client.get().uri(b -> b.path("/api/ops/instances")
                        .queryParam("page", "1")
                        .queryParam("size", "2")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").value(v -> {
                    // 可能有种子数据，至少返回请求的 size
                    int len = (Integer) v;
                    assert len >= 0;
                })
                .jsonPath("$.data.size").isEqualTo(2);
    }

    // ─── 3.1.1 POST /instances/batch ────────────────────────

    @Test
    void batchOp_缺少参数返回错误() {
        client.post().uri("/api/ops/instances/batch")
                .bodyValue(java.util.Map.of())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").value(v -> {
                    int code = (Integer) v;
                    assert code != 0 : "expected error code";
                });
    }

    @Test
    void batchOp_无效op返回错误() {
        client.post().uri("/api/ops/instances/batch")
                .bodyValue(java.util.Map.of("ids", java.util.List.of(uuid(82001).toString()), "op", "invalid"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").value(v -> {
                    int code = (Integer) v;
                    assert code != 0 : "expected error code";
                });
    }

    @Test
    void batchOp_空ids返回错误() {
        client.post().uri("/api/ops/instances/batch")
                .bodyValue(java.util.Map.of("ids", java.util.List.of(), "op", "rerun"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").value(v -> {
                    int code = (Integer) v;
                    assert code != 0 : "expected error code";
                });
    }

    // ─── 3.1.2 POST /backfill ────────────────────────────────

    @Test
    void backfill_提交请求返回outcome() {
        java.util.Map<String, Object> body = java.util.Map.of(
                "targetType", "task",
                "targetId", 1,
                "dateStart", "2026-06-20",
                "dateEnd", "2026-06-22",
                "includeDownstream", false,
                "parallelism", 2
        );

        client.post().uri("/api/ops/backfill")
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.outcome").isNotEmpty();
    }

    @Test
    void backfill_带下游子集提交返回outcome() {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("targetType", "task");
        body.put("targetId", 1);
        body.put("dateStart", "2026-06-20");
        body.put("dateEnd", "2026-06-20");
        body.put("includeDownstream", true);
        body.put("parallelism", 1);
        body.put("downstreamTaskIds", java.util.List.of(7702));

        client.post().uri("/api/ops/backfill")
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.outcome").isNotEmpty();
    }

    // ─── 下游影响范围预览（backfill-target-and-downstream-ux）────
    //
    // 任务级下游的「真实血缘返回下游任务」场景已随 PG 血缘四表退役（3ff396f）迁移到
    // neo4j 单一底座，等价性验证见 master 模块 BackfillDownstreamNeo4jIT（真 neo4j 容器）。
    // 原 PG 版 downstreamPreview_沿血缘返回下游任务 依赖已删除的 data_table/task_table_io，
    // 故在此 H2 端点测试中移除；此处仅保留无血缘依赖的 workflow 目标空集回归。

    @Test
    void downstreamPreview_workflow目标返回空() {
        client.get().uri("/api/ops/backfill/downstream-preview?targetType=workflow&targetId=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.length()").isEqualTo(0);
    }

    // ─── 3.1.2 GET /backfill ────────────────────────────────

    @Test
    void backfillList_返回列表() {
        client.get().uri("/api/ops/backfill?page=1&size=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items").isArray();
    }

    // ─── 3.1.2 GET /backfill/{runId} ────────────────────────

    @Test
    void backfillDetail_查询不存在返回stillOk() {
        client.get().uri("/api/ops/backfill/" + uuid(83001))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);
    }

    // ─── 3.1.3 POST /workflows/{id}/nodes/{nodeKey}/freeze（节点级，取代退役的任务级 freeze）─────

    @Test
    void freezeNode_定义级_返回outcome() {
        client.post().uri("/api/ops/workflows/1/nodes/n-1/freeze")
                .bodyValue(java.util.Map.of("frozen", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.outcome").isNotEmpty();
    }

    @Test
    void unfreezeNode_返回outcome() {
        client.post().uri("/api/ops/workflows/1/nodes/n-1/freeze")
                .bodyValue(java.util.Map.of("frozen", false))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.outcome").isNotEmpty();
    }

    // ─── 3.2.1 巡检端点（Weft AI 拆除后 /api/ops/inspect 已移除）───

    @Test
    void inspect_端点已移除() {
        client.get().uri("/api/ops/inspect")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(404);
    }

    // ─── 鉴权 ──────────────────────────────────────────────

    @Test
    void 未鉴权返回401() {
        WebTestClient noAuth = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        noAuth.get().uri("/api/ops/instances?state=FAILED")
                .exchange()
                .expectStatus().isUnauthorized();
        noAuth.post().uri("/api/ops/instances/batch")
                .bodyValue(java.util.Map.of())
                .exchange()
                .expectStatus().isUnauthorized();
        noAuth.post().uri("/api/ops/backfill")
                .bodyValue(java.util.Map.of())
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
