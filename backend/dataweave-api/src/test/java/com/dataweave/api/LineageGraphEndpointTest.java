package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import com.dataweave.master.application.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

/**
 * 表级血缘图 REST 契约（table-lineage / lineage-cockpit）：建任务即建血缘 → 全局图含节点与流边。
 * 挂 h2 profile，零外部依赖可跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class LineageGraphEndpointTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TaskService taskService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
    }

    @Test
    void createTaskViaService_recordsLineage_andGraphServes() {
        // 端到端 keystone：建任务即建血缘（Agent 声明 + SQL 解析一致 → CONFIRMED）
        taskService.createAndOnline(
                "lineage-endpoint-test", "SQL",
                "INSERT INTO dwd_lt_order SELECT * FROM ods_lt_order",
                "0 0 8 * * ?", 1L, 2L,
                List.of("ods_lt_order"), List.of("dwd_lt_order"));

        client.get().uri("/api/lineage/graph")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.nodes[?(@.qualifiedName=='ods_lt_order')]").exists()
                .jsonPath("$.data.nodes[?(@.qualifiedName=='dwd_lt_order')]").exists()
                // 命名前缀推导 layer
                .jsonPath("$.data.nodes[?(@.qualifiedName=='dwd_lt_order')].layer").isEqualTo("DWD")
                // 表→表流边（ods → dwd 经该任务）
                .jsonPath("$.data.edges[0].confidence").isEqualTo("CONFIRMED");
    }

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
}
