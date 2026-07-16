package com.dataweave.api;

import java.time.Duration;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 071 US3 对话后端契约（T025）：
 * ① brain 不可用 → {@code companion.brain_unavailable}（明确降级，非空白）；② GET /messages 历史；③ cancel 走 L0 闸门留痕。
 * 独立 H2 库；无真 workhorse（forChat 为 empty → chat 直接降级）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-companion-us3-071;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "companion.patrol.enabled=false",
        // 契约=「无 brain」：必须钉死到必死端口。默认 127.0.0.1:8300 上若恰有真 workhorse 在跑,
        // chat 会真成功导致断言反转(部署收口实测踩坑)
        "companion.brain.base-url=http://127.0.0.1:9"
})
@DisplayName("Companion US3 对话/降级/打断 契约（071）")
class CompanionUs3IT {

    @LocalServerPort
    int port;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    JdbcTemplate jdbc;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .defaultHeader("X-Project-Id", "1")
                .responseTimeout(Duration.ofSeconds(20))
                .build();
        jdbc.update("DELETE FROM companion_message");
    }

    @Test
    @DisplayName("chat 无 brain → code=companion.brain_unavailable（非空白降级）")
    void chat_brainUnavailable_returnsErrorCode() {
        client.post().uri("/api/companion/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"content\":\"你好\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").value((Integer c) -> org.assertj.core.api.Assertions.assertThat(c).isNotEqualTo(0))
                .jsonPath("$.errorCode").isEqualTo("companion.brain_unavailable");
    }

    @Test
    @DisplayName("GET /messages 返回历史消息（全局会话）")
    void messages_returnsHistory() {
        jdbc.update("INSERT INTO companion_message (tenant_id, project_id, report_id, role, actor, actor_name, " +
                "content, created_at) VALUES (1, 1, NULL, 'AGENT', 'companion-agent', 'Vega', '系统正常', CURRENT_TIMESTAMP)");

        client.get().uri("/api/companion/messages")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[0].role").isEqualTo("AGENT")
                .jsonPath("$.data[0].actorName").isEqualTo("Vega")
                .jsonPath("$.data[0].content").isEqualTo("系统正常");
    }

    @Test
    @DisplayName("chat/cancel 走 L0 闸门留痕（agent_action 有 COMPANION_CHAT_CANCEL）")
    void cancel_auditedViaGate() {
        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_action WHERE action_type = 'COMPANION_CHAT_CANCEL'", Integer.class);

        client.post().uri("/api/companion/chat/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(0);

        Integer after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_action WHERE action_type = 'COMPANION_CHAT_CANCEL'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(after)
                .isGreaterThan(before == null ? 0 : before);   // L0 执行 + 审计留痕
    }
}
