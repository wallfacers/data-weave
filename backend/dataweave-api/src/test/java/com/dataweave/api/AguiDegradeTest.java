package com.dataweave.api;

import com.dataweave.api.application.bridge.WorkhorseHealth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 优雅降级：默认 workhorse 模式但 workhorse 不可用（{@link WorkhorseHealth} 返回 false）时，
 * 编排自动走 IntentRouter mock 路径，前置降级提示，且仍产出合法 AG-UI 序列与结构化结果。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agent.mode=workhorse"})
@ActiveProfiles("h2")
class AguiDegradeTest {

    @TestConfiguration
    static class StubConfig {
        /** 模拟 workhorse 不可用，强制走降级路径。 */
        @Bean
        @Primary
        WorkhorseHealth unhealthyWorkhorse() {
            return () -> false;
        }
    }

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void workhorseUnavailable_degradesToMock_withNotice_andValidSequence() {
        String body = client.post().uri("/agui")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("threadId", "deg-t", "runId", "deg-r",
                        "messages", List.of(Map.of("role", "user", "content", "GMV 是多少"))))
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).isNotNull();
        List<Map<String, Object>> events = parse(body);
        List<String> types = events.stream().map(e -> (String) e.get("type")).toList();

        // 合法 AG-UI 序列
        assertThat(types.get(0)).isEqualTo("RUN_STARTED");
        assertThat(types.get(types.size() - 1)).isEqualTo("RUN_FINISHED");
        assertThat(types).filteredOn("TEXT_MESSAGE_START"::equals).hasSize(1);
        assertThat(types).filteredOn("TEXT_MESSAGE_END"::equals).hasSize(1);
        assertThat(types).filteredOn("TEXT_MESSAGE_CONTENT"::equals).hasSizeGreaterThanOrEqualTo(1);

        // 降级提示出现在文本里（agent.degraded.notice）
        assertThat(body).contains("规则引擎");

        // 仍走了 IntentRouter：GMV 指标查询的结构化结果
        Map<String, Object> result = events.stream()
                .filter(e -> "CUSTOM".equals(e.get("type")) && "dataweave.result".equals(e.get("name")))
                .findFirst().orElse(null);
        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.get("value");
        assertThat(value).containsEntry("kind", "metric");
    }

    private List<Map<String, Object>> parse(String sseBody) {
        List<Map<String, Object>> events = new ArrayList<>();
        if (sseBody == null) {
            return events;
        }
        for (String line : sseBody.split("\n")) {
            String t = line.trim();
            if (t.startsWith("data:")) {
                try {
                    events.add(objectMapper.readValue(t.substring(5).trim(), Map.class));
                } catch (Exception ignored) {
                    // skip
                }
            }
        }
        return events;
    }
}
