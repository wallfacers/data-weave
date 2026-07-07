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
}
