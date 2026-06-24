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
 * 自有聊天台多会话 REST 契约（agent-chat-shell）：新建→列出→追加消息→历史重水合→删除。h2 零依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class AgentSessionEndpointTest {

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
    void sessionLifecycle_createAppendHistoryDelete() {
        // 新建会话（断言契约 + 取回 id）
        Long sessionId = createAndGetId("OOM 排查会话");

        // 追加一条消息
        client.post().uri("/api/agent/sessions/" + sessionId + "/messages")
                .bodyValue(Map.of("role", "user", "partsJson", "[{\"type\":\"text\",\"content\":\"为什么挂了\"}]"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(0);

        // 历史重水合：1 条
        client.get().uri("/api/agent/sessions/" + sessionId + "/history")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].role").isEqualTo("user");

        // 列表含该会话
        client.get().uri("/api/agent/sessions")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[?(@.id==" + sessionId + ")]").exists();

        // 删除
        client.delete().uri("/api/agent/sessions/" + sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(0);

        // 删除后列表不含
        client.get().uri("/api/agent/sessions")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[?(@.id==" + sessionId + ")]").doesNotExist();
    }

    private Long createAndGetId(String title) {
        byte[] body = client.post().uri("/api/agent/sessions")
                .bodyValue(Map.of("title", title))
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        String s = new String(body);
        int i = s.indexOf("\"id\":");
        int start = i + 5;
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)))) {
            end++;
        }
        return Long.parseLong(s.substring(start, end).trim());
    }
}
