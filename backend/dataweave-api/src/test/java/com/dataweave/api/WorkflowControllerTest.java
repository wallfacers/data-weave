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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkflowController 端到端测试（workflow-authoring / workflow-canvas spec）：
 * 创建 → 整图保存 → 读图 → 发布。HTTP 状态统一 200，业务态读 code 字段。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class WorkflowControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JwtUtil jwtUtil;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .defaultHeader("X-Project-Id", "1")
                .build();
    }

    @Test
    void fullLifecycle_createSaveDagReadPublish() throws Exception {
        // 1) 创建草稿
        byte[] createBody = client.post().uri("/api/workflows")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "E2E 画布工作流"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(0)
                .returnResult().getResponseBody();
        JsonNode created = objectMapper.readTree(createBody).get("data");
        long id = created.get("id").asLong();
        long version = created.get("version").asLong();

        // 2) 整图保存：虚拟起始 → 任务
        String dag = objectMapper.writeValueAsString(Map.of(
                "version", version,
                "nodes", java.util.List.of(
                        Map.of("nodeKey", "v0", "nodeType", "VIRTUAL", "name", "开始", "posX", 0, "posY", 0),
                        Map.of("nodeKey", "t1", "nodeType", "TASK", "taskId", 1, "name", "任务1", "posX", 120, "posY", 0)),
                "edges", java.util.List.of(Map.of("fromNodeKey", "v0", "toNodeKey", "t1"))));
        client.put().uri("/api/workflows/{id}/dag", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dag)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.nodes.length()").isEqualTo(2)
                .jsonPath("$.data.edges.length()").isEqualTo(1);

        // 3) 读图
        client.get().uri("/api/workflows/{id}/dag", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.nodes.length()").isEqualTo(2);

        // 4) 发布
        client.post().uri("/api/workflows/{id}/publish", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("remark", "首发"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.status").isEqualTo("ONLINE")
                .jsonPath("$.data.currentVersionNo").isEqualTo(1);
    }

    @Test
    void saveDraft_persistsConfigAndDagInOneCall() throws Exception {
        byte[] createBody = client.post().uri("/api/workflows")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "原子保存-初始"))
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        JsonNode created = objectMapper.readTree(createBody).get("data");
        long id = created.get("id").asLong();
        long version = created.get("version").asLong();

        String body = objectMapper.writeValueAsString(Map.of(
                "config", Map.of("name", "原子保存-改名", "priority", 7),
                "dag", Map.of("version", version,
                        "nodes", java.util.List.of(
                                Map.of("nodeKey", "t1", "nodeType", "TASK", "taskId", 1, "name", "任务1", "posX", 0, "posY", 0)),
                        "edges", java.util.List.of())));
        client.put().uri("/api/workflows/{id}/draft", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.nodes.length()").isEqualTo(1);

        // 配置与 DAG 同时落库
        client.get().uri("/api/workflows/{id}", id)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.workflow.name").isEqualTo("原子保存-改名")
                .jsonPath("$.data.workflow.priority").isEqualTo(7);
    }

    @Test
    void saveDraft_staleVersionRollsBackConfig() throws Exception {
        byte[] createBody = client.post().uri("/api/workflows")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "回滚-初始"))
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        long id = objectMapper.readTree(createBody).get("data").get("id").asLong();

        // 故意带过期 DAG version → saveDag 抛 409；整事务回滚，配置改名不应落库。
        String body = objectMapper.writeValueAsString(Map.of(
                "config", Map.of("name", "回滚-不该生效"),
                "dag", Map.of("version", 999,
                        "nodes", java.util.List.of(), "edges", java.util.List.of())));
        client.put().uri("/api/workflows/{id}/draft", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(409);

        client.get().uri("/api/workflows/{id}", id)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.workflow.name").isEqualTo("回滚-初始");
    }

    @Test
    void search_returnsPage() {
        client.get().uri("/api/workflows?page=0&size=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.content").exists();
    }
}
