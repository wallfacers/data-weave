package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 血缘图 REST 契约（h2）：端点 shape 验证 + 空态。
 *
 * <p>血缘查询端到端（search/paths/impact/neighborhood/upstream/downstream）由 neo4j IT 覆盖
 * （LineageSeamE2EIT、LineageGraphExplorerE2EIT 等），h2 无 neo4j 不验证血缘数据。
 * 本类仅测试不依赖 Neo4j 的兼容端点和空查询快速路径。
 *
 * <p>挂 h2 profile，零外部依赖可跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class LineageGraphEndpointTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .defaultHeader("X-Project-Id", "1")
                .build();
    }

    // ─── 兼容端点（不依赖 Neo4j） ─────────────────────────────

    @Test
    void graphContractShape_okWrappedArrays() {
        client.get().uri("/api/lineage/graph")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.nodes").isArray()
                .jsonPath("$.data.edges").isArray();
    }

    @Test
    void etaSummary_h2预测端点契约_运行中实例给出非空ETA() {
        client.get().uri("/api/ops/eta-summary")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.remainingSeconds").exists()
                .jsonPath("$.data.predictedCount").exists();
    }

    // ─── 052 search 空查询快速路径 ─────────────────────────────

    @Test
    void search_emptyQ_returnsEmptyArray() {
        // 空关键词 → 服务层直接返回 []，不经 Neo4j（FR-011：空结果 [] 非报错）
        client.get().uri("/api/lineage/search?q=")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data").isArray()
                .jsonPath("$.data.length()").isEqualTo(0);
    }

    // ─── 054 向后兼容 + 项目隔离守卫（FR-020） ─────────────────
    // 054 新增的节点 attrs(datasourceId/datasourceName) 与 SearchCandidate.datasourceName 富化
    // 由真 Neo4j IT（LineageDatasourceProjectionIT）覆盖；h2 无 Neo4j，这里锁「形状不破 + 隔离不漏」。

    @Test
    void graphLegacyContract_preservesLineageGraphShape() {
        // /api/lineage/graph 恒返回空 LineageGraph；锁其 record 字段形状（向后兼容，DTO 富化不改既有字段）。
        client.get().uri("/api/lineage/graph")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.nodes").isArray()
                .jsonPath("$.data.edges").isArray()
                .jsonPath("$.data.granularity").exists()
                .jsonPath("$.data.depth").exists()
                .jsonPath("$.data.truncated").exists();
    }

    @Test
    void search_withoutProject_returnsProjectRequired() {
        // 不带 X-Project-Id（亦无 projectId 参数）→ project() 抛 project.required（FR-020 项目隔离守卫，
        // 在任何 Neo4j 查询之前）。HTTP 恒 200，业务状态经 code/errorCode 表达（GlobalExceptionHandler 约定）。
        WebTestClient noProjectClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
        noProjectClient.get().uri("/api/lineage/search?q=user")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(400)
                .jsonPath("$.errorCode").isEqualTo("project.required");
    }

    // ─── 054 US3 分面端点：项目隔离守卫（数据正确性由 LineageFacetTablesIT 覆盖） ───

    @Test
    void tablesByDatasource_withoutProject_returnsProjectRequired() {
        // 分面「数据源」端点：无 X-Project-Id → project.required（隔离守卫在 Neo4j 之前）。
        WebTestClient noProjectClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
        noProjectClient.get().uri("/api/lineage/datasources/ds-a/tables")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(400)
                .jsonPath("$.errorCode").isEqualTo("project.required");
    }

    @Test
    void tablesByLayer_withoutProject_returnsProjectRequired() {
        // 分面「分层」端点：无 X-Project-Id → project.required。
        WebTestClient noProjectClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
        noProjectClient.get().uri("/api/lineage/tables?layer=DWD")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(400)
                .jsonPath("$.errorCode").isEqualTo("project.required");
    }
}
