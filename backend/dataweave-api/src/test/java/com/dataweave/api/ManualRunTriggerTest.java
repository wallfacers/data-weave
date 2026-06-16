package com.dataweave.api;

import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * manual-run-trigger 端到端（H2，all-in-one 内存总线）：
 * <ul>
 *   <li>已发布任务 → 闸门 L1 直执行，返回 run_mode=NORMAL 的正式实例（计入统计、跑已发布版本、独立实例）；</li>
 *   <li>已上线工作流 → trigger_type=MANUAL 的正式 workflow_instance；</li>
 *   <li>草稿任务 / 未上线工作流 → 闸门前即拒（code=409）；</li>
 *   <li>policy_rules 收紧 run_task → L2 → PENDING_APPROVAL（数据驱动抬级）。</li>
 * </ul>
 * 复用 data.sql 种子：task id=1（GMV 统计，ONLINE v1）、workflow id=1（每日 GMV，ONLINE v1）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class ManualRunTriggerTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    TaskInstanceRepository taskInstanceRepository;
    @Autowired
    WorkflowInstanceRepository workflowInstanceRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void runTask_published_executesNormalInstanceAndAudits() throws Exception {
        byte[] body = client.post().uri("/api/tasks/1/run")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("bizDate", "2026-06-16"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.outcome").isEqualTo("EXECUTED")
                .returnResult().getResponseBody();
        UUID instanceId = UUID.fromString(objectMapper.readTree(body).get("data").get("resultInstanceId").asText());

        TaskInstance ti = taskInstanceRepository.findById(instanceId).orElseThrow();
        assertThat(ti.getRunMode()).isEqualTo("NORMAL");        // 正式、计入统计（OpsService 按 NORMAL 过滤）
        assertThat(ti.getTaskVersionNo()).isEqualTo(1);         // 跑已发布版本
        assertThat(ti.getWorkflowInstanceId()).isNull();        // 独立实例（非工作流节点）

        // 写操作必经闸门并落 agent_action 留痕
        Integer audited = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_action WHERE action_type='TASK_RUN'", Integer.class);
        assertThat(audited).isGreaterThan(0);
    }

    @Test
    void runWorkflow_online_executesManualInstance() throws Exception {
        byte[] body = client.post().uri("/api/workflows/1/run")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("bizDate", "2026-06-16"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.outcome").isEqualTo("EXECUTED")
                .returnResult().getResponseBody();
        UUID wiId = UUID.fromString(objectMapper.readTree(body).get("data").get("resultInstanceId").asText());

        WorkflowInstance wi = workflowInstanceRepository.findById(wiId).orElseThrow();
        assertThat(wi.getTriggerType()).isEqualTo("MANUAL");    // 薄包装 WorkflowTriggerService.trigger(MANUAL)
        assertThat(wi.getWorkflowId()).isEqualTo(1L);
    }

    @Test
    void runTask_draft_rejectedBeforeGate() throws Exception {
        long draftId = createDraftTask("草稿任务-不可跑");
        client.post().uri("/api/tasks/{id}/run", draftId)
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(409)
                .jsonPath("$.message").value(containsString("发布"));
    }

    @Test
    void runWorkflow_draft_rejectedBeforeGate() throws Exception {
        long draftId = createDraftWorkflow("草稿工作流-不可跑");
        client.post().uri("/api/workflows/{id}/run", draftId)
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(409)
                .jsonPath("$.message").value(containsString("上线"));
    }

    @Test
    void runTask_tightenedRule_pendingApproval() {
        // 收紧：run_task → L2（审批）。sort_order=5 先于种子 L1（sort_order=20）命中。
        jdbcTemplate.update("INSERT INTO policy_rules (match_type, pattern, base_level, enabled, sort_order, version) "
                + "VALUES ('TOOL', 'run_task', 'L2', 1, 5, 0)");
        try {
            client.post().uri("/api/tasks/1/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(0)
                    .jsonPath("$.data.outcome").isEqualTo("PENDING_APPROVAL")
                    .jsonPath("$.data.actionId").isNotEmpty();
        } finally {
            jdbcTemplate.update("DELETE FROM policy_rules "
                    + "WHERE match_type='TOOL' AND pattern='run_task' AND base_level='L2'");
        }
    }

    // ---- helpers ----

    /** 创建一个草稿任务（status=DRAFT），返回其 id。 */
    private long createDraftTask(String name) throws Exception {
        byte[] body = client.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", name, "type", "SQL", "content", "select 1"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(0)
                .returnResult().getResponseBody();
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }

    /** 创建一个草稿工作流（status=DRAFT），返回其 id。 */
    private long createDraftWorkflow(String name) throws Exception {
        byte[] body = client.post().uri("/api/workflows")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", name))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(0)
                .returnResult().getResponseBody();
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }
}
