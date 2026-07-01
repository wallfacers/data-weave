package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 036 项目隔离 A 路集成测试：运维中心调度 + 运行态总览 + 实例表按 projectId 隔离。
 *
 * <p>双项目 A/B 各造实例，断言各 ops 端点跨项目 0 串；bizDate 切换收敛正确。
 * H2 内存库，零外部依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class OpsProjectIsolationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbc;

    private WebTestClient client;

    private static final long PROJECT_A = 9001L;
    private static final long PROJECT_B = 9002L;
    private static final String BIZ_DATE_TODAY = LocalDate.now().toString();
    private static final String BIZ_DATE_YESTERDAY = LocalDate.now().minusDays(1).toString();

    @BeforeEach
    void setUp() {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();

        // 清理上次测试的数据，防止 H2 固定库名跨测试类污染
        jdbc.update("DELETE FROM task_instance WHERE project_id IN (?, ?)", PROJECT_A, PROJECT_B);
        jdbc.update("DELETE FROM workflow_instance WHERE project_id IN (?, ?)", PROJECT_A, PROJECT_B);
        jdbc.update("DELETE FROM backfill_run WHERE project_id IN (?, ?)", PROJECT_A, PROJECT_B);

        // 确保项目存在并添加用户为成员（ProjectScope.require 需要）
        ensureProjectAndMembership(PROJECT_A);
        ensureProjectAndMembership(PROJECT_B);
    }

    private void ensureProjectAndMembership(long projectId) {
        // 幂等创建项目
        jdbc.update("INSERT INTO projects (id, tenant_id, code, name, owner_id, status, created_by, updated_by, created_at, updated_at, deleted, version) "
                + "SELECT ?, 1, ?, ?, 1, 'ACTIVE', 1, 1, NOW(), NOW(), 0, 0 "
                + "WHERE NOT EXISTS (SELECT 1 FROM projects WHERE id=?)",
                projectId, "proj-" + projectId, "测试项目" + projectId, projectId);
        // 幂等添加成员
        jdbc.update("INSERT INTO project_member (id, tenant_id, project_id, user_id, role_id, created_by, updated_by, created_at, updated_at, deleted, version) "
                + "SELECT ?, 1, ?, 1, 1, 1, 1, NOW(), NOW(), 0, 0 "
                + "WHERE NOT EXISTS (SELECT 1 FROM project_member WHERE tenant_id=1 AND project_id=? AND user_id=1 AND deleted=0)",
                projectId * 100, projectId, projectId);
    }

    private UUID insertTaskInstance(UUID id, long projectId, String bizDate, String state, String runMode) {
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, task_id, run_mode, state, biz_date, deleted) "
                + "VALUES (?, 1, ?, 1, ?, ?, ?, 0)", id, projectId, runMode, state, bizDate);
        return id;
    }

    private UUID insertWorkflowInstance(UUID id, long projectId, String bizDate, String state) {
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, biz_date, deleted) "
                + "VALUES (?, 1, ?, 1, ?, ?, 0)", id, projectId, state, bizDate);
        return id;
    }

    // ═══════════════════════════════════════════════════════════
    // instances 端点
    // ═══════════════════════════════════════════════════════════

    @Test
    void instances_projectA_onlyReturnsProjectA() {
        UUID idA = insertTaskInstance(UUID.randomUUID(), PROJECT_A, BIZ_DATE_TODAY, "SUCCESS", "NORMAL");
        UUID idB = insertTaskInstance(UUID.randomUUID(), PROJECT_B, BIZ_DATE_TODAY, "SUCCESS", "NORMAL");

        var result = client.get()
                .uri("/api/ops/instances?projectId=" + PROJECT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        // 验证返回结果不包含项目 B 的实例
        String responseBody = new String(result.returnResult().getResponseBody());
        assertThat(responseBody).contains(idA.toString());
        assertThat(responseBody).doesNotContain(idB.toString());
    }

    @Test
    void instances_projectB_onlyReturnsProjectB() {
        UUID idA = insertTaskInstance(UUID.randomUUID(), PROJECT_A, BIZ_DATE_TODAY, "SUCCESS", "NORMAL");
        UUID idB = insertTaskInstance(UUID.randomUUID(), PROJECT_B, BIZ_DATE_TODAY, "SUCCESS", "NORMAL");

        var result = client.get()
                .uri("/api/ops/instances?projectId=" + PROJECT_B)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        String responseBody = new String(result.returnResult().getResponseBody());
        assertThat(responseBody).contains(idB.toString());
        assertThat(responseBody).doesNotContain(idA.toString());
    }

    @Test
    void instances_bizDateFilter_convergesCorrectly() {
        UUID idToday = insertTaskInstance(UUID.randomUUID(), PROJECT_A, BIZ_DATE_TODAY, "SUCCESS", "NORMAL");
        UUID idYesterday = insertTaskInstance(UUID.randomUUID(), PROJECT_A, BIZ_DATE_YESTERDAY, "SUCCESS", "NORMAL");

        // 只查今天的
        var result = client.get()
                .uri("/api/ops/instances?projectId=" + PROJECT_A + "&bizDate=" + BIZ_DATE_TODAY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        String responseBody = new String(result.returnResult().getResponseBody());
        assertThat(responseBody).contains(idToday.toString());
        assertThat(responseBody).doesNotContain(idYesterday.toString());
    }

    // ═══════════════════════════════════════════════════════════
    // summary 端点
    // ═══════════════════════════════════════════════════════════

    @Test
    void summary_projectA_countsOnlyProjectA() {
        insertTaskInstance(UUID.randomUUID(), PROJECT_A, BIZ_DATE_TODAY, "SUCCESS", "NORMAL");
        insertTaskInstance(UUID.randomUUID(), PROJECT_A, BIZ_DATE_TODAY, "FAILED", "NORMAL");
        insertTaskInstance(UUID.randomUUID(), PROJECT_B, BIZ_DATE_TODAY, "SUCCESS", "NORMAL");
        insertTaskInstance(UUID.randomUUID(), PROJECT_B, BIZ_DATE_TODAY, "SUCCESS", "NORMAL");

        client.get()
                .uri("/api/ops/summary?projectId=" + PROJECT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.total").isEqualTo(2)
                .jsonPath("$.data.success").isEqualTo(1)
                .jsonPath("$.data.failed").isEqualTo(1);
    }

    @Test
    void summary_projectB_countsOnlyProjectB() {
        insertTaskInstance(UUID.randomUUID(), PROJECT_A, BIZ_DATE_TODAY, "SUCCESS", "NORMAL");
        insertTaskInstance(UUID.randomUUID(), PROJECT_B, BIZ_DATE_TODAY, "SUCCESS", "NORMAL");
        insertTaskInstance(UUID.randomUUID(), PROJECT_B, BIZ_DATE_TODAY, "SUCCESS", "NORMAL");

        client.get()
                .uri("/api/ops/summary?projectId=" + PROJECT_B)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.total").isEqualTo(2)
                .jsonPath("$.data.success").isEqualTo(2);
    }

    // ═══════════════════════════════════════════════════════════
    // failed 端点
    // ═══════════════════════════════════════════════════════════

    @Test
    void failedInstances_projectIsolation() {
        UUID failA = insertTaskInstance(UUID.randomUUID(), PROJECT_A, BIZ_DATE_TODAY, "FAILED", "NORMAL");
        UUID failB = insertTaskInstance(UUID.randomUUID(), PROJECT_B, BIZ_DATE_TODAY, "FAILED", "NORMAL");

        var result = client.get()
                .uri("/api/ops/failed?projectId=" + PROJECT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        String responseBody = new String(result.returnResult().getResponseBody());
        assertThat(responseBody).contains(failA.toString());
        assertThat(responseBody).doesNotContain(failB.toString());
    }

    // ═══════════════════════════════════════════════════════════
    // workflow-instances 端点
    // ═══════════════════════════════════════════════════════════

    @Test
    void workflowInstances_projectIsolation() {
        UUID wiA = insertWorkflowInstance(UUID.randomUUID(), PROJECT_A, BIZ_DATE_TODAY, "SUCCESS");
        UUID wiB = insertWorkflowInstance(UUID.randomUUID(), PROJECT_B, BIZ_DATE_TODAY, "SUCCESS");

        var result = client.get()
                .uri("/api/ops/workflow-instances?projectId=" + PROJECT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        String responseBody = new String(result.returnResult().getResponseBody());
        assertThat(responseBody).contains(wiA.toString());
        assertThat(responseBody).doesNotContain(wiB.toString());
    }

    @Test
    void workflowInstances_bizDateConvergesTrue() {
        UUID wiToday = insertWorkflowInstance(UUID.randomUUID(), PROJECT_A, BIZ_DATE_TODAY, "SUCCESS");
        UUID wiYesterday = insertWorkflowInstance(UUID.randomUUID(), PROJECT_A, BIZ_DATE_YESTERDAY, "SUCCESS");

        var result = client.get()
                .uri("/api/ops/workflow-instances?projectId=" + PROJECT_A + "&bizDate=" + BIZ_DATE_TODAY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        String responseBody = new String(result.returnResult().getResponseBody());
        assertThat(responseBody).contains(wiToday.toString());
        assertThat(responseBody).doesNotContain(wiYesterday.toString());
    }

    // ═══════════════════════════════════════════════════════════
    // 缺 projectId 返回错误
    // ═══════════════════════════════════════════════════════════

    @Test
    void missingProjectId_returnsRequiredError() {
        // 传入无效 projectId=0 → ProjectScope.require 抛 project.required
        // 项目约定：业务错误 HTTP 200 + ApiResponse.code != 0
        client.get()
                .uri("/api/ops/instances?projectId=0")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(400)
                .jsonPath("$.errorCode").isEqualTo("project.required");
    }

    // ═══════════════════════════════════════════════════════════
    // backfill 列表端点
    // ═══════════════════════════════════════════════════════════

    @Test
    void backfillList_projectIsolation() {
        // 在项目 A 和 B 各插入一个 backfill_run
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        jdbc.update("INSERT INTO backfill_run (id, tenant_id, project_id, target_type, target_id, target_name, "
                + "date_start, date_end, parallelism, include_downstream, state, created_by, created_at, deleted) "
                + "VALUES (?, 1, ?, 'task', 1, 'testA', ?, ?, 1, false, 'RUNNING', 1, NOW(), 0)",
                runA, PROJECT_A, BIZ_DATE_TODAY, BIZ_DATE_TODAY);
        jdbc.update("INSERT INTO backfill_run (id, tenant_id, project_id, target_type, target_id, target_name, "
                + "date_start, date_end, parallelism, include_downstream, state, created_by, created_at, deleted) "
                + "VALUES (?, 1, ?, 'task', 1, 'testB', ?, ?, 1, false, 'RUNNING', 1, NOW(), 0)",
                runB, PROJECT_B, BIZ_DATE_TODAY, BIZ_DATE_TODAY);

        var result = client.get()
                .uri("/api/ops/backfill?projectId=" + PROJECT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        String responseBody = new String(result.returnResult().getResponseBody());
        assertThat(responseBody).contains(runA.toString());
        assertThat(responseBody).doesNotContain(runB.toString());
    }

    // ═══════════════════════════════════════════════════════════
    // eta-summary 端点
    // ═══════════════════════════════════════════════════════════

    @Test
    void etaSummary_acceptsProjectId() {
        client.get()
                .uri("/api/ops/eta-summary?projectId=" + PROJECT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);
    }

    // ═══════════════════════════════════════════════════════════
    // periodic-workflows 端点
    // ═══════════════════════════════════════════════════════════

    @Test
    void periodicWorkflows_acceptsProjectId() {
        client.get()
                .uri("/api/ops/periodic-workflows?projectId=" + PROJECT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);
    }
}
