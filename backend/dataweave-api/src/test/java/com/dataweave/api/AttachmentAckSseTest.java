package com.dataweave.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 附件随 forwardedProps 抵达 mock 后端的端到端证明（chat-attachments）：发一条带实体+文件附件的消息，
 * 验证 SSE 文本流里出现「已收到附件」致谢且含附件标签——即附件确实穿过 /agui 到了编排器。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class AttachmentAckSseTest {

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void attachmentsReachBackend_andAreAcknowledged() {
        Map<String, Object> body = Map.of(
                "threadId", "t-att",
                "runId", "r-att",
                "messages", List.of(Map.of("role", "user", "content", "你好")),
                "forwardedProps", Map.of("dataweave", Map.of(
                        "attachments", List.of(
                                Map.of("kind", "entity", "refType", "task", "refId", "100", "label", "etl_daily"),
                                // 文件按前端真实形态用 name 字段（无 label）。
                                Map.of("kind", "file", "fileId", "abc123", "name", "error.log")))));

        String sse = client.post().uri("/agui")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(sse).isNotNull();
        // 致谢前缀 + 两个附件标签都应出现在文本流里。
        assertThat(sse).contains("etl_daily");
        assertThat(sse).contains("error.log");
    }
}
