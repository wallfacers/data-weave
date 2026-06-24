package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import com.dataweave.master.application.OpsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最近活跃实例查询（run-state-resume）回归：
 * - OpsService 派生查询：NORMAL/TEST 过滤、UUIDv7 id 降序取最新、无实例返回 null；
 * - OpsController HTTP 契约：200 + $.data、runMode 过滤、无实例 data=null、未鉴权 401。
 *
 * <p>用独立大 taskId/workflowId（不与 data.sql 种子或其他测试类冲突），UUIDv7 显式构造保证 id 序确定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class OpsLatestInstanceTest {

    @LocalServerPort
    int port;

    @Autowired
    OpsService opsService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtUtil jwtUtil;

    WebTestClient client;

    // 独立 id 段，避免与种子/他测污染
    static final long TASK_ID = 770101L;
    static final long TASK_ID_EMPTY = 770999L;
    static final long WF_ID = 770201L;
    static final long WF_ID_EMPTY = 770998L;

    static UUID uuidv7(int seq) {
        // 形如 00000000-0000-7000-8000-0000000000XX，字典序≈插入序（模拟 UUIDv7 时间序）
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seq));
    }

    void insertTaskInstance(UUID id, long taskId, String runMode, String state) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO task_instance (id, tenant_id, project_id, task_id, run_mode, state, attempt, "
                        + "created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, ?, ?, 0, ?, ?, 0, 0)",
                id, taskId, runMode, state, now, now);
    }

    void insertWorkflowInstance(UUID id, long workflowId, String state) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, biz_date, "
                        + "started_at, finished_at, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, ?, '2026-06-23', ?, NULL, ?, ?, 0, 0)",
                id, workflowId, state, now, now, now);
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
    }

    @Test
    void latestTaskInstance_取最新NORMAL实例() {
        insertTaskInstance(uuidv7(1001), TASK_ID, "NORMAL", "SUCCESS");
        insertTaskInstance(uuidv7(1002), TASK_ID, "NORMAL", "RUNNING"); // 更晚 → 应取这条

        OpsService.LatestInstanceView v = opsService.latestTaskInstance(TASK_ID, null); // 默认 NORMAL
        assertThat(v).isNotNull();
        assertThat(v.id()).isEqualTo(uuidv7(1002));
        assertThat(v.state()).isEqualTo("RUNNING");
        assertThat(v.runMode()).isEqualTo("NORMAL");
    }

    @Test
    void latestTaskInstance_按runMode过滤不串台() {
        long tid = TASK_ID + 1;
        insertTaskInstance(uuidv7(2001), tid, "NORMAL", "FAILED");
        insertTaskInstance(uuidv7(2002), tid, "TEST", "RUNNING");

        OpsService.LatestInstanceView normal = opsService.latestTaskInstance(tid, "NORMAL");
        assertThat(normal.id()).isEqualTo(uuidv7(2001));
        assertThat(normal.runMode()).isEqualTo("NORMAL");

        OpsService.LatestInstanceView test = opsService.latestTaskInstance(tid, "TEST");
        assertThat(test.id()).isEqualTo(uuidv7(2002));
        assertThat(test.runMode()).isEqualTo("TEST");
    }

    @Test
    void latestTaskInstance_无实例返回null() {
        assertThat(opsService.latestTaskInstance(TASK_ID_EMPTY, "NORMAL")).isNull();
    }

    @Test
    void latestWorkflowInstance_取最新() {
        insertWorkflowInstance(uuidv7(3001), WF_ID, "SUCCESS");
        insertWorkflowInstance(uuidv7(3002), WF_ID, "RUNNING");

        OpsService.LatestInstanceView v = opsService.latestWorkflowInstance(WF_ID);
        assertThat(v).isNotNull();
        assertThat(v.id()).isEqualTo(uuidv7(3002));
        assertThat(v.state()).isEqualTo("RUNNING");
        assertThat(v.runMode()).isEqualTo("NORMAL");
    }

    @Test
    void latestWorkflowInstance_无实例返回null() {
        assertThat(opsService.latestWorkflowInstance(WF_ID_EMPTY)).isNull();
    }

    @Test
    void httpTaskLatest_默认NORMAL契约() {
        long tid = TASK_ID + 10;
        insertTaskInstance(uuidv7(4001), tid, "NORMAL", "RUNNING");
        client.get().uri("/api/ops/tasks/{id}/latest-instance", tid)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.state").isEqualTo("RUNNING")
                .jsonPath("$.data.runMode").isEqualTo("NORMAL");
    }

    @Test
    void httpTaskLatest_runModeTEST过滤() {
        long tid = TASK_ID + 11;
        insertTaskInstance(uuidv7(4101), tid, "TEST", "SUCCESS");
        client.get().uri(b -> b.path("/api/ops/tasks/{id}/latest-instance").queryParam("runMode", "TEST").build(tid))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.runMode").isEqualTo("TEST")
                .jsonPath("$.data.state").isEqualTo("SUCCESS");
    }

    @Test
    void httpTaskLatest_无实例data为null() {
        client.get().uri("/api/ops/tasks/{id}/latest-instance", TASK_ID_EMPTY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data").isEmpty();
    }

    @Test
    void httpWorkflowLatest_契约() {
        long wid = WF_ID + 10;
        insertWorkflowInstance(uuidv7(5001), wid, "FAILED");
        client.get().uri("/api/ops/workflows/{id}/latest-instance", wid)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.state").isEqualTo("FAILED");
    }

    @Test
    void httpLatest_未鉴权401() {
        WebTestClient noAuth = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        noAuth.get().uri("/api/ops/tasks/{id}/latest-instance", TASK_ID)
                .exchange()
                .expectStatus().isUnauthorized();
        noAuth.get().uri("/api/ops/workflows/{id}/latest-instance", WF_ID)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
