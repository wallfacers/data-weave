package com.dataweave.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AG-UI SSE 端点端到端测试。
 *
 * <p>验证 POST /agui 的事件序列完整性、CUSTOM 负载正确性。
 * 使用 {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} 真实启动到 PostgreSQL。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AguiEndpointTest {

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    /**
     * 发一条「GMV 是多少」的消息，验证完整 AG-UI 事件序列：
     * RUN_STARTED → TEXT_MESSAGE_START → N×TEXT_MESSAGE_CONTENT → TEXT_MESSAGE_END
     * → CUSTOM(name=dataweave.result, kind=metric) → RUN_FINISHED。
     */
    @Test
    void shouldReturnCompleteAguiEventSequenceForMetricQuery() {
        // given
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("threadId", "t");
        requestBody.put("runId", "r");
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", "GMV 是多少")
        ));

        // when
        String responseBody = webTestClient.post()
                .uri("/agui")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // then —— 解析 SSE 事件
        assertThat(responseBody).isNotNull();

        List<Map<String, Object>> events = parseSseEvents(responseBody);
        assertThat(events).isNotEmpty();

        // 提取事件 type 序列
        List<String> types = events.stream()
                .map(e -> (String) e.get("type"))
                .toList();

        // 第一个是 RUN_STARTED
        assertThat(types.get(0)).isEqualTo("RUN_STARTED");

        // 最后一个是 RUN_FINISHED
        assertThat(types.get(types.size() - 1)).isEqualTo("RUN_FINISHED");

        // 有且仅有一个 TEXT_MESSAGE_START、一个 TEXT_MESSAGE_END
        assertThat(types.stream().filter("TEXT_MESSAGE_START"::equals).count()).isEqualTo(1);
        assertThat(types.stream().filter("TEXT_MESSAGE_END"::equals).count()).isEqualTo(1);

        // TEXT_MESSAGE_START 在所有 TEXT_MESSAGE_CONTENT 之前
        int startIdx = types.indexOf("TEXT_MESSAGE_START");
        assertThat(startIdx).isGreaterThanOrEqualTo(0);
        List<Integer> contentIndices = indicesOf(types, "TEXT_MESSAGE_CONTENT");
        assertThat(contentIndices).isNotEmpty();
        assertThat(contentIndices).allMatch(i -> i > startIdx);

        // TEXT_MESSAGE_END 在所有 TEXT_MESSAGE_CONTENT 之后
        int endIdx = types.indexOf("TEXT_MESSAGE_END");
        assertThat(endIdx).isGreaterThanOrEqualTo(0);
        assertThat(contentIndices).allMatch(i -> i < endIdx);

        // 至少一个 TEXT_MESSAGE_CONTENT
        assertThat(contentIndices).hasSizeGreaterThanOrEqualTo(1);

        // 含一个 CUSTOM 事件，name=="dataweave.result"
        List<Map<String, Object>> customEvents = events.stream()
                .filter(e -> "CUSTOM".equals(e.get("type")))
                .toList();
        assertThat(customEvents).hasSize(1);

        Map<String, Object> custom = customEvents.get(0);
        assertThat(custom).containsEntry("name", "dataweave.result");

        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) custom.get("value");
        assertThat(value).isNotNull();
        assertThat(value).containsEntry("kind", "metric");
        assertThat(value).containsEntry("name", "GMV");
    }

    // ---- helpers ----

    /**
     * 解析 SSE 响应体。每行 "data:" 后的 JSON 解析为 Map。
     */
    private List<Map<String, Object>> parseSseEvents(String sseBody) {
        List<Map<String, Object>> events = new ArrayList<>();
        if (sseBody == null || sseBody.isBlank()) {
            return events;
        }
        for (String line : sseBody.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                String json = trimmed.substring(5).trim();
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = objectMapper.readValue(json, Map.class);
                    events.add(event);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse SSE data: " + json, e);
                }
            }
        }
        return events;
    }

    /** 返回列表中所有匹配项的索引。 */
    private static List<Integer> indicesOf(List<String> list, String value) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (value.equals(list.get(i))) {
                indices.add(i);
            }
        }
        return indices;
    }
}
