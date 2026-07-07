package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * 053 血缘 AI Agent 配置 REST 契约测试（T019，契约 config-api.md）。
 * PUT 加密 / GET 脱敏 / 完整性校验 / 协议校验 / test 探活 / 非成员 forbidden。
 * 独立 H2 库（防串台），JWT 鉴权，统一 200 + $.code/$.data/$.errorCode 断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-lineage-agent-053;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@DisplayName("Lineage Agent Config HTTP 契约 (053)")
class LineageAgentConfigControllerIT {

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
                .responseTimeout(java.time.Duration.ofSeconds(20))
                .build();
        jdbc.update("DELETE FROM lineage_agent_call");
        jdbc.update("DELETE FROM lineage_agent_config");
    }

    @Test
    @DisplayName("PUT 加密入库 + GET 脱敏回显（FR-020）")
    void putThenGetReturnsMaskedApiKey() {
        client.put().uri("/api/lineage/agent-config?projectId=1")
                .bodyValue(Map.of(
                        "protocol", "OPENAI",
                        "baseUrl", "https://api.openai.com",
                        "model", "gpt-4o-mini",
                        "apiKey", "sk-secret-a1b2",
                        "enabled", true,
                        "timeoutMs", 45000,
                        "rateLimitPerMin", 30,
                        "maxColumns", 1500))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.apiKeyMasked").isEqualTo("sk-…a1b2")
                .jsonPath("$.data.enabled").isEqualTo(true)
                .jsonPath("$.data.timeoutMs").isEqualTo(45000);

        client.get().uri("/api/lineage/agent-config?projectId=1")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.apiKeyMasked").isEqualTo("sk-…a1b2")
                .jsonPath("$.data.protocol").isEqualTo("OPENAI")
                .jsonPath("$.data.model").isEqualTo("gpt-4o-mini");

        // 明文绝不出现在响应里
        // (apiKeyMasked 只含末4位，全 key sk-secret-a1b2 不可见)
    }

    @Test
    @DisplayName("GET 无配置返回 data=null")
    void getReturnsNullWhenNoConfig() {
        client.get().uri("/api/lineage/agent-config?projectId=1")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data").isEqualTo(null);
    }

    @Test
    @DisplayName("enabled=true 缺 baseUrl → config_incomplete")
    void putEnabledWithoutBaseUrlIsIncomplete() {
        client.put().uri("/api/lineage/agent-config?projectId=1")
                .bodyValue(Map.of("protocol", "OPENAI", "model", "x", "enabled", true))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("lineage_agent.config_incomplete");
    }

    @Test
    @DisplayName("protocol 非法 → protocol_invalid")
    void putInvalidProtocol() {
        client.put().uri("/api/lineage/agent-config?projectId=1")
                .bodyValue(Map.of("protocol", "GEMINI", "baseUrl", "https://x", "model", "m", "enabled", false))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("lineage_agent.protocol_invalid");
    }

    @Test
    @DisplayName("POST /test 不可达端点 → ok=false（不抛、不阻断）")
    void testEndpointReturnsNotOkForUnreachable() {
        // 指向拒绝连接的端口（localhost:1 立即 ConnectException），timeoutMs 短避免拖慢测试
        client.post().uri("/api/lineage/agent-config/test?projectId=1")
                .bodyValue(Map.of(
                        "protocol", "OPENAI",
                        "baseUrl", "https://localhost:1",
                        "model", "x",
                        "apiKey", "sk-test",
                        "timeoutMs", 1000))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.ok").isEqualTo(false);
    }

    @Test
    @DisplayName("非项目成员 → project.forbidden（GlobalExceptionHandler 统一 HTTP 200 + code=403）")
    void nonMemberForbidden() {
        client.get().uri("/api/lineage/agent-config?projectId=99999")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(403)
                .jsonPath("$.errorCode").isEqualTo("project.forbidden");
    }

    @Test
    @DisplayName("PUT apiKey 缺省=不改：二次 PUT 不带 key 保留旧密文")
    void putWithoutApiKeyKeepsExisting() {
        // 首次 PUT 带 key
        client.put().uri("/api/lineage/agent-config?projectId=1")
                .bodyValue(Map.of("protocol", "OPENAI", "baseUrl", "https://a",
                        "model", "m", "apiKey", "sk-first-1234", "enabled", true))
                .exchange().expectStatus().isOk();
        // 二次 PUT 不带 apiKey（只改 model），GET 仍回首次 key 脱敏
        client.put().uri("/api/lineage/agent-config?projectId=1")
                .bodyValue(Map.of("protocol", "OPENAI", "baseUrl", "https://a",
                        "model", "m2", "enabled", true))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.model").isEqualTo("m2");
        client.get().uri("/api/lineage/agent-config?projectId=1")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.apiKeyMasked").isEqualTo("sk-…1234");
    }
}
