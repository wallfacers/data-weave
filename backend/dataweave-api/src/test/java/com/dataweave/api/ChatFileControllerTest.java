package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatFileController HTTP 层端到端（chat-attachments）：multipart 上传 → 拿 id → 下载回取字节，
 * 并验证 sha256 内容寻址去重（同内容两次上传得同 id）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class ChatFileControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
    }

    @Test
    void upload_thenDownload_roundTrips() {
        byte[] content = "2026-06-24 ERROR OutOfMemoryError: Java heap space\n".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> idRef = new AtomicReference<>();

        client.post().uri("/api/chat/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart("error.log", content).build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.name").isEqualTo("error.log")
                .jsonPath("$.data.size").isEqualTo(content.length)
                .jsonPath("$.data.id").value(id -> idRef.set((String) id));

        byte[] back = client.get().uri("/api/chat/files/" + idRef.get())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches(HttpHeaders.CONTENT_DISPOSITION, ".*error\\.log.*")
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        assertThat(back).isEqualTo(content);
    }

    @Test
    void sameContent_dedupesToSameId() {
        byte[] content = "select 1;".getBytes(StandardCharsets.UTF_8);
        String id1 = uploadAndGetId("a.sql", content);
        String id2 = uploadAndGetId("b.sql", content);
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void download_missing_returnsBizError() {
        // 平台契约：HTTP 永远 200，业务状态在 code（缺文件 → BizException withHttpStatus(404) → code=404）。
        client.get().uri("/api/chat/files/deadbeef-not-exist")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(404);
    }

    private String uploadAndGetId(String name, byte[] content) {
        AtomicReference<String> idRef = new AtomicReference<>();
        client.post().uri("/api/chat/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart(name, content).build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.id").value(id -> idRef.set((String) id));
        return idRef.get();
    }

    private static MultipartBodyBuilder multipart(String filename, byte[] content) {
        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.TEXT_PLAIN);
        return b;
    }
}
