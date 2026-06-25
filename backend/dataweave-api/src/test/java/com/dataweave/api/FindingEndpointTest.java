package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * 通用发现 REST 契约（proactive-discovery）：举手台列表 + 一键修复经闸门分流。h2 零依赖。
 *
 * <p>OOM 演示种子（finding id=1 / 诊断）已移至 demo profile（demo-data.sql），故本测试显式叠加
 * {@code demo} profile 引入该素材；默认启动不含演示假数据（agent-real-brain change）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"h2", "demo"})
class FindingEndpointTest {

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
                .build();
    }

    @Test
    void listThenApply_seededOomFinding_passesGate() {
        // 先列出：举手台含种子 OOM 发现（TASK_FAILURE）。
        client.get().uri("/api/findings")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[?(@.source=='TASK_FAILURE')]").exists();

        // 再修复：经 GatedActionService 闸门；不预设 outcome（EXECUTED/PENDING_APPROVAL 取决于 policy_rules），
        // 只断言契约：200 + code 0 + outcome 字段存在（前端按它分流）。注意：执行后该发现可能被置 RESOLVED，
        // 故 list 必须在 apply 之前断言。
        client.post().uri("/api/findings/1/apply")
                .bodyValue(Map.of("actionKey", "RERUN_MORE_MEMORY"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.outcome").exists()
                .jsonPath("$.data.message").exists();
    }
}
