package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * 资产目录 + 指标市场端到端契约测试（quickstart 场景 1-4/6-8）。
 *
 * <p>独立随机 H2 库名隔离（避免跨测试类污染）；带 JWT；HTTP 200 + {@code $.code/$.data/$.errorCode} 契约。
 * 写经闸门 → 返回 GateResult（含 actionId = agent_action 审计，证零旁路）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-asset023;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@DisplayName("资产目录 + 指标市场契约 (023)")
class AssetCatalogIT {

    @LocalServerPort
    int port;
    @Autowired
    JwtUtil jwtUtil;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                // neo4j 不可达时 driver 连接超时 5s 后降级返回，读超时须 > 5s（血缘降级路径）。
                .responseTimeout(java.time.Duration.ofSeconds(15))
                .build();
    }

    // 场景 1：编目 + 去重 + 写闸门审计（actionId）
    @Test
    @DisplayName("场景1 编目资产 → EXECUTED+actionId；同名再编目 → catalog.duplicate_asset")
    void catalogAndDedup() {
        client.post().uri("/api/catalog/assets")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("datasourceId", 1, "qualifiedName", "qa_new_tbl", "name", "QA 表", "sensitivity", "INTERNAL"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.outcome").isEqualTo("EXECUTED")
                .jsonPath("$.data.actionId").isNotEmpty();      // 闸门审计行 id

        client.post().uri("/api/catalog/assets")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("datasourceId", 1, "qualifiedName", "qa_new_tbl", "name", "重复"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.errorCode").isEqualTo("catalog.duplicate_asset");
    }

    // 场景 2：分面搜索
    @Test
    @DisplayName("场景2 关键词搜索命中 + 分面计数")
    void facetedSearch() {
        client.get().uri("/api/catalog/assets?keyword=订单&page=1&size=20&projectId=1")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.items.length()").value(v -> org.assertj.core.api.Assertions.assertThat((int) v).isGreaterThanOrEqualTo(1))
                .jsonPath("$.data.facets.sensitivity").exists();
    }

    // 场景 3：血缘消费 + 降级（SC-002，neo4j 离线）
    @Test
    @DisplayName("场景3 血缘入口 neo4j 离线 → degraded，主功能不受影响")
    void lineageDegradesGracefully() {
        client.get().uri("/api/catalog/assets/1/lineage?projectId=1")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.degraded").isEqualTo(true)
                .jsonPath("$.data.available").isEqualTo(false);
        // 主功能（详情）照常
        client.get().uri("/api/catalog/assets/1")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.qualifiedName").isEqualTo("dwd_order");
    }

    // 场景 4：上架复用防环 + 认证 L2
    @Test
    @DisplayName("场景4 复用防环 → catalog.reuse_cycle；认证 L2 → PENDING_APPROVAL")
    void reuseCycleAndCertifyL2() {
        // 1 复用 2（建边 2→1）
        client.post().uri("/api/marketplace/metrics/1/reuse")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("consumerType", "METRIC", "consumerRef", "2"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.outcome").isEqualTo("EXECUTED");
        // 2 再复用 1 → 成环
        client.post().uri("/api/marketplace/metrics/2/reuse")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("consumerType", "METRIC", "consumerRef", "1"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.errorCode").isEqualTo("catalog.reuse_cycle");
        // 认证（L2）→ 待审批，不直接执行
        client.post().uri("/api/marketplace/metrics/2/certify")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.outcome").isEqualTo("PENDING_APPROVAL");
    }

    // 场景 6：敏感度可见性（SC-006）
    @Test
    @DisplayName("场景6 他人 PII 资产不可搜出；属主可见自己的 PII")
    void sensitivityVisibility() {
        // 建一条他人(owner=999) PII 资产
        client.post().uri("/api/catalog/assets")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("datasourceId", 1, "qualifiedName", "secret_pii_tbl", "sensitivity", "PII", "ownerId", 999))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.outcome").isEqualTo("EXECUTED");
        // 调用者(user=1)搜不到他人 PII
        client.get().uri("/api/catalog/assets?keyword=secret_pii&projectId=1")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.items.length()").isEqualTo(0);
        // 但属主可见自己的 PII（seed 资产3 ods_user owner=1）
        client.get().uri("/api/catalog/assets/3")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.sensitivity").isEqualTo("PII");
    }

    // 场景 8：STALE 对账
    @Test
    @DisplayName("场景8 底层表缺失 → 对账后 STALE")
    void reconcileToStale() {
        client.post().uri("/api/catalog/assets/4/reconcile")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.outcome").isEqualTo("EXECUTED");
        client.get().uri("/api/catalog/assets/4")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("STALE");
    }
}
