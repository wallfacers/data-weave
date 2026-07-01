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
 * 血缘图 REST 契约（h2）：/graph 兼容端点 shape + eta-summary 端点契约。
 *
 * <p>血缘写读端到端（建任务入图、sync-summary 聚合）由 neo4j IT 覆盖（LineageSeamE2EIT 等），
 * h2 无 neo4j 不验证血缘数据。PG 血缘四表已退役（018 neo4j 收口）。挂 h2 profile，零外部依赖可跑。
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

    @Test
    void graphContractShape_okWrappedArrays() {
        // /graph 兼容端点：返空图（nodes/edges 为空数组），shape 契约稳定。
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
        // H2 方言验证：predictLatestEta 的 LIMIT ? + 时长中位数在 H2 跑通。
        client.get().uri("/api/ops/eta-summary")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.remainingSeconds").exists()
                .jsonPath("$.data.predictedCount").exists();
    }
}
