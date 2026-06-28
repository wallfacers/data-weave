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
 * DAG 节点详情端点契约测试（004-dag-node-detail-panel）。
 * GET /api/ops/workflows/{workflowId}/nodes/{nodeKey}/detail
 *
 * <p>基于 data.sql seed 数据：workflow 3（订单 SHELL 流水线，ONLINE）
 * 发布快照含 6 个 TASK 节点（n1-n6），taskId=10..15，taskVersionNo=1。
 * task_def_version 有对应记录。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"h2"})
class NodeDetailEndpointTest {

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

    /** TASK 节点应返回完整详情 */
    @Test
    void shouldReturnDetailForTaskNode() {
        client.get()
                .uri("/api/ops/workflows/3/nodes/n1/detail")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.nodeKey").isEqualTo("n1")
                .jsonPath("$.data.taskId").isEqualTo(10)
                .jsonPath("$.data.taskName").isEqualTo("抽取-拉取订单分区")
                .jsonPath("$.data.taskType").isEqualTo("ECHO")  // wf3 演示节点用 ECHO(沙箱可执行,见 data.sql 注释)
                .jsonPath("$.data.versionNo").isEqualTo(1)
                .jsonPath("$.data.hasCode").isEqualTo(true)
                .jsonPath("$.data.deleted").isEqualTo(false)
                .jsonPath("$.data.content").isNotEmpty();
    }

    /** 不存在的 nodeKey 返回错误码（BizException → HTTP 200 + code≠0） */
    @Test
    void shouldReturnErrorForMissingNode() {
        client.get()
                .uri("/api/ops/workflows/3/nodes/nonexistent/detail")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(404)
                .jsonPath("$.data").doesNotExist();
    }

    /** 不存在的工作流返回错误码 */
    @Test
    void shouldReturnErrorForMissingWorkflow() {
        client.get()
                .uri("/api/ops/workflows/99999/nodes/n1/detail")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(404)
                .jsonPath("$.data").doesNotExist();
    }
}
