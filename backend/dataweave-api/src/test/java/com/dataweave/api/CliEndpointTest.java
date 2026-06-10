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
                .expectBody().jsonPath("$").isArray();
    }

    @Test
    void rerun_withoutToken_returns401() {
        client.post().uri("/api/cli/instances/1/rerun").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rerun_wrongToken_returns401() {
        client.post().uri("/api/cli/instances/1/rerun")
                .header("X-DW-Token", "nope").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rerun_withToken_goesThroughGate() {
        client.post().uri("/api/cli/instances/1/rerun")
                .header("X-DW-Token", TOKEN).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.outcome").exists()
                .jsonPath("$.level").exists();
    }
}
