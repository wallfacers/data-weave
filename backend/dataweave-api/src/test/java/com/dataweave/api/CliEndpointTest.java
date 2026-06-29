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

/**
 * dw CLI REST 契约（015 后认证合并为单一 Bearer）：读类需 Bearer JWT；写类 rerun 经闸门，
 * 需 Bearer JWT 或 X-DW-Token（过渡期兼容）——无任何凭据 → JwtAuthFilter 直接 HTTP 401，
 * 错 token → 经控制器由 GlobalExceptionHandler 表达为 body code=401。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class CliEndpointTest {

    @Autowired
    private JwtUtil jwtUtil;

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
        // 015 后 /api/cli 移出 JwtAuthFilter 白名单：读类同样需 Bearer JWT（CLI 统一 DW_TOKEN）
        client.get().uri("/api/cli/tasks")
                .header("Authorization", JwtTestSupport.bearer(jwtUtil))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data").isArray();
    }

    @Test
    void rerun_withoutToken_returns401() {
        // 015 后 /api/cli 移出白名单：无 Bearer 也无 X-DW-Token → JwtAuthFilter 直接返回 HTTP 401
        // （区别于"错 token"——后者经控制器由 GlobalExceptionHandler 表达为 body code=401）
        client.post().uri("/api/cli/instances/" + INSTANCE_ID + "/rerun").exchange()
                .expectStatus().isUnauthorized();
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
