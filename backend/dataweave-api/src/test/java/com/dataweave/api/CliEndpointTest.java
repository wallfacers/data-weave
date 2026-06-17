package com.dataweave.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * dw CLI REST 契约：读类直通；写类 rerun 经闸门并需 token（无/错 token → 401）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class CliEndpointTest {

    private static final String TOKEN = "dataweave-local-cli-token";
    /** 实例 id 为 UUID（task_instance 主键 UUID7）；用合法 UUID 触达 token 校验与闸门，非 "1"。 */
    private static final String INSTANCE_ID = "11111111-1111-1111-1111-111111111111";

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(15)).build();
    }

    @Test
    void taskList_returnsDefinitions() {
        client.get().uri("/api/cli/tasks").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data").isArray();
    }

    @Test
    void rerun_withoutToken_returns401() {
        // HTTP 统一 200，鉴权失败经 GlobalExceptionHandler 表达为 body code=401
        client.post().uri("/api/cli/instances/" + INSTANCE_ID + "/rerun").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(401);
    }

    @Test
    void rerun_wrongToken_returns401() {
        client.post().uri("/api/cli/instances/" + INSTANCE_ID + "/rerun")
                .header("X-DW-Token", "nope").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(401);
    }

    @Test
    void rerun_withToken_goesThroughGate() {
        client.post().uri("/api/cli/instances/" + INSTANCE_ID + "/rerun")
                .header("X-DW-Token", TOKEN).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.outcome").exists()
                .jsonPath("$.data.level").exists();
    }
}
