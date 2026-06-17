package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

/**
 * Workspace 快照 REST（workspace-persistence spec）：写读回环、无快照空态、覆盖更新、超长拒绝。
 * 挂 h2 profile，零外部依赖可跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class WorkspaceEndpointTest {

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

    private String uri(String conversationId) {
        return "/api/agent/sessions/" + conversationId + "/workspace";
    }

    @Test
    void noSnapshot_returnsEmptyData() {
        // 契约：HTTP 统一 200，无快照 → ApiResponse(code=0, data=null)，前端据此回落 Pinned 底座
        client.get().uri(uri("conv-" + UUID.randomUUID()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data").isEmpty();
    }

    @Test
    void putThenGet_roundTripsJson() {
        String conv = "conv-" + UUID.randomUUID();
        String snapshot = "{\"version\":1,\"tabs\":[{\"view\":\"fleet\",\"pinned\":false}],\"activeTabId\":\"fleet\"}";

        client.put().uri(uri(conv))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(snapshot)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        client.get().uri(uri(conv))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data").isEqualTo(snapshot);
    }

    @Test
    void put_overwritesPreviousSnapshot() {
        String conv = "conv-" + UUID.randomUUID();
        String first = "{\"version\":1,\"tabs\":[],\"activeTabId\":\"cockpit\"}";
        String second = "{\"version\":1,\"tabs\":[{\"view\":\"lineage\",\"pinned\":true}],\"activeTabId\":\"lineage\"}";

        client.put().uri(uri(conv)).contentType(MediaType.APPLICATION_JSON).bodyValue(first)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.code").isEqualTo(0);
        client.put().uri(uri(conv)).contentType(MediaType.APPLICATION_JSON).bodyValue(second)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.code").isEqualTo(0);

        client.get().uri(uri(conv))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data").isEqualTo(second);
    }

    @Test
    void oversizedSnapshot_isRejected() {
        String conv = "conv-" + UUID.randomUUID();
        String huge = "{\"pad\":\"" + "x".repeat(9000) + "\"}";

        client.put().uri(uri(conv))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(huge)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(413);
    }
}
