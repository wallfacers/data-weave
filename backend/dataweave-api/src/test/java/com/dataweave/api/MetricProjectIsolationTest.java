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

import static org.hamcrest.Matchers.nullValue;

/**
 * 036 FR-012：指标读接口按 projectId 隔离 + 业务日期观察（全栈 H2 + JWT）。
 *
 * <p>造双项目（tenant=1，project=901/902，避开 seed 的 project=1 指标 GMV/ORDER_CNT）指标定义，断言：
 * ① {@code /api/metrics?projectId=901} 只返回项目 901 的定义（同 code 不串、他项目专属 code 不泄漏）；
 * ② {@code bizDate} 按源表 {@code biz_date} 列收敛到当日快照；缺数据 → value=null 明确空态。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class MetricProjectIsolationTest {

    private static final long PROJ_A = 901L;
    private static final long PROJ_B = 902L;

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtUtil jwtUtil;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM atomic_metrics WHERE id BETWEEN 870000 AND 879999");
        jdbc.update("DROP TABLE IF EXISTS metric_iso_test");
        jdbc.update("CREATE TABLE metric_iso_test (biz_date VARCHAR(32), amount BIGINT)");
        // 两个业务日期的快照；2026-06-28 当日无数据
        jdbc.update("INSERT INTO metric_iso_test VALUES ('2026-06-30', 100)");
        jdbc.update("INSERT INTO metric_iso_test VALUES ('2026-06-30', 20)");
        jdbc.update("INSERT INTO metric_iso_test VALUES ('2026-06-29', 40)");

        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
    }

    /** 插入一条原子指标（指向测试源表，口径 sum(amount)）。 */
    void insertMetric(long id, long projectId, String code) {
        jdbc.update("INSERT INTO atomic_metrics (id, tenant_id, project_id, code, name, datasource_id, "
                        + "source_table, measure_expr, agg_type, unit, owner_id, version_no, status, "
                        + "created_by, updated_by, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, ?, ?, ?, 1, 'metric_iso_test', 'sum(amount)', 'SUM', '元', "
                        + "1, 1, 'ONLINE', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)",
                id, projectId, code, code);
    }

    @Test
    void metrics_项目隔离_只返回当前项目定义() {
        insertMetric(870001L, PROJ_A, "ISO_GMV");
        insertMetric(870002L, PROJ_B, "ISO_GMV");   // 同 code 不同项目
        insertMetric(870003L, PROJ_B, "ISO_OTHER"); // 项目 B 专属

        // 项目 A：仅 ISO_GMV(870001)，ISO_OTHER 不泄漏
        client.get().uri(b -> b.path("/api/metrics").queryParam("projectId", PROJ_A).build())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].id").isEqualTo(870001)
                .jsonPath("$.data[0].code").isEqualTo("ISO_GMV");

        // 项目 B：ISO_GMV(870002) + ISO_OTHER(870003)
        client.get().uri(b -> b.path("/api/metrics").queryParam("projectId", PROJ_B).build())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.length()").isEqualTo(2);
    }

    @Test
    void metrics_项目隔离_跨项目定义不串号() {
        // 项目 A 与项目 B 各有一条 id 不同、code 相同的定义
        insertMetric(870011L, PROJ_A, "ISO_SHARED");
        insertMetric(870012L, PROJ_B, "ISO_SHARED");

        // 项目 A 只见 870011，项目 B 只见 870012，绝不互串
        client.get().uri(b -> b.path("/api/metrics").queryParam("projectId", PROJ_A).build())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].id").isEqualTo(870011);
        client.get().uri(b -> b.path("/api/metrics").queryParam("projectId", PROJ_B).build())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].id").isEqualTo(870012);
    }

    @Test
    void metrics_bizDate按日期返回对应快照() {
        insertMetric(870021L, PROJ_A, "ISO_GMV");
        // 2026-06-30: 100+20=120
        client.get().uri(b -> b.path("/api/metrics")
                        .queryParam("projectId", PROJ_A).queryParam("bizDate", "2026-06-30").build())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[0].value").isEqualTo(120);
        // 2026-06-29: 40
        client.get().uri(b -> b.path("/api/metrics")
                        .queryParam("projectId", PROJ_A).queryParam("bizDate", "2026-06-29").build())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].value").isEqualTo(40);
    }

    @Test
    void metrics_bizDate无数据时value为空态() {
        insertMetric(870031L, PROJ_A, "ISO_GMV");
        // 当日无数据 → sum 为 NULL → value=null（明确空态，禁止借显他日期）
        client.get().uri(b -> b.path("/api/metrics")
                        .queryParam("projectId", PROJ_A).queryParam("bizDate", "2026-06-28").build())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[0].code").isEqualTo("ISO_GMV")
                .jsonPath("$.data[0].value").value(nullValue());
    }

    @Test
    void metrics_缺projectId_结构化错误() {
        // 既无 TenantContext.projectId（地基未注入）也无查询参数 → project.required
        // BizException 默认 httpStatus=400，GlobalExceptionHandler 回传 code=400 + errorCode（非 500）
        client.get().uri("/api/metrics")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(400)
                .jsonPath("$.errorCode").isEqualTo("project.required");
    }
}
