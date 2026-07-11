package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import com.dataweave.master.application.FlinkSavepointClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 062 US1 实时任务面板端点契约（GET /api/ops/streaming-tasks）：
 * - 仅 long_running=TRUE 实例入列（批实例被排除）；
 * - state / keyword 过滤；项目隔离（X-Project-Id）；
 * - 关联最近 SUCCESS 检查点 → lastCheckpoint 非空；
 * - 未鉴权 401。
 *
 * <p>独立大 id 段（不撞种子/他测）；h2 profile 就地建库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class StreamingTasksWebTest {

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtUtil jwtUtil;
    @MockitoBean
    FlinkSavepointClient flinkSavepointClient;  // 替换真 Flink REST，测 stop 端点接线

    WebTestClient client;

    static final long TASK_A = 620101L;  // 实时任务 mysql-sync
    static final long TASK_B = 620102L;  // 实时任务 kafka-etl
    static final long TASK_BATCH = 620103L;  // 批任务（非 long_running）

    static UUID uuidv7(int seq) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-6201%08d", seq));
    }

    void insert(UUID id, long projectId, long taskId, String taskName, String state,
                boolean longRunning, String handle) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO task_instance (id, tenant_id, project_id, task_id, task_def_name, run_mode, state, "
                        + "long_running, external_job_handle, business_attempt, infra_redispatch_count, attempt, "
                        + "started_at, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, ?, ?, ?, 'NORMAL', ?, ?, ?, 0, 0, 0, ?, ?, ?, 0, 0)",
                id, projectId, taskId, taskName, state, longRunning, handle, now, now, now);
    }

    void insertCheckpoint(UUID instanceId, int ordinal, String status) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO task_checkpoint (id, task_instance_id, ordinal, checkpoint_path, status, "
                        + "completed_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), instanceId, ordinal, "hdfs:///sp/" + ordinal, status,
                "SUCCESS".equals(status) ? now : null, now);
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .defaultHeader("X-Project-Id", "1")
                .build();
        jdbc.update("DELETE FROM task_checkpoint WHERE task_instance_id IN "
                + "(SELECT id FROM task_instance WHERE task_id IN (?,?,?))", TASK_A, TASK_B, TASK_BATCH);
        jdbc.update("DELETE FROM task_instance WHERE task_id IN (?,?,?)", TASK_A, TASK_B, TASK_BATCH);
    }

    @Test
    void onlyLongRunning_批实例被排除() {
        insert(uuidv7(1), 1, TASK_A, "mysql-sync-realtime", "RUNNING", true, "{\"jobId\":\"j1\"}");
        insert(uuidv7(2), 1, TASK_B, "kafka-etl-realtime", "SUSPENDED", true, null);
        insert(uuidv7(3), 1, TASK_BATCH, "daily-batch", "SUCCESS", false, null);  // 非 long_running

        client.get().uri("/api/ops/streaming-tasks")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.total").isEqualTo(2)
                .jsonPath("$.data.items[?(@.taskName=='daily-batch')]").doesNotExist();
    }

    @Test
    void stateFilter() {
        insert(uuidv7(11), 1, TASK_A, "mysql-sync-realtime", "RUNNING", true, "{\"jobId\":\"j1\"}");
        insert(uuidv7(12), 1, TASK_B, "kafka-etl-realtime", "SUSPENDED", true, null);

        client.get().uri(b -> b.path("/api/ops/streaming-tasks").queryParam("state", "SUSPENDED").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.items[0].state").isEqualTo("SUSPENDED");
    }

    @Test
    void keywordFilter() {
        insert(uuidv7(21), 1, TASK_A, "mysql-sync-realtime", "RUNNING", true, null);
        insert(uuidv7(22), 1, TASK_B, "kafka-etl-realtime", "RUNNING", true, null);

        client.get().uri(b -> b.path("/api/ops/streaming-tasks").queryParam("keyword", "kafka").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.items[0].taskName").isEqualTo("kafka-etl-realtime");
    }

    @Test
    void projectIsolation_他项目不可见() {
        insert(uuidv7(31), 2, TASK_A, "other-project-stream", "RUNNING", true, null);  // project 2

        client.get().uri("/api/ops/streaming-tasks")  // X-Project-Id=1
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.total").isEqualTo(0);
    }

    @Test
    void lastCheckpoint_关联最近SUCCESS() {
        insert(uuidv7(41), 1, TASK_A, "mysql-sync-realtime", "STOPPED", true, null);
        insertCheckpoint(uuidv7(41), 1, "SUCCESS");
        insertCheckpoint(uuidv7(41), 2, "SUCCESS");

        client.get().uri("/api/ops/streaming-tasks")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.items[0].lastCheckpoint.ordinal").isEqualTo(2)
                .jsonPath("$.data.items[0].lastCheckpoint.resumable").isEqualTo(true)
                .jsonPath("$.data.items[0].externalJobHandlePresent").isEqualTo(false);
    }

    @Test
    void stop_成功_触发savepoint写检查点置STOPPED() {
        UUID id = uuidv7(51);
        insert(id, 1, TASK_A, "mysql-sync-realtime", "RUNNING", true, HANDLE);
        when(flinkSavepointClient.stopWithSavepoint(anyString(), anyString(), any()))
                .thenReturn("hdfs:///sp/stopped-1");

        client.post().uri("/api/ops/streaming-tasks/{id}/stop", id)
                .bodyValue("{}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.state").isEqualTo("STOPPED")
                .jsonPath("$.data.checkpointPath").isEqualTo("hdfs:///sp/stopped-1");

        // DB 落地：实例 STOPPED + 检查点 SUCCESS
        String state = jdbc.queryForObject("SELECT state FROM task_instance WHERE id=?", String.class, id);
        Integer cpCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_checkpoint WHERE task_instance_id=? AND status='SUCCESS'",
                Integer.class, id);
        org.assertj.core.api.Assertions.assertThat(state).isEqualTo("STOPPED");
        org.assertj.core.api.Assertions.assertThat(cpCount).isEqualTo(1);
    }

    @Test
    void stop_savepoint不可用_错误envelope() {
        UUID id = uuidv7(52);
        insert(id, 1, TASK_A, "mysql-sync-realtime", "RUNNING", true, HANDLE);
        when(flinkSavepointClient.stopWithSavepoint(anyString(), anyString(), any()))
                .thenThrow(new FlinkSavepointClient.SavepointException("引擎不可达"));

        client.post().uri("/api/ops/streaming-tasks/{id}/stop", id)
                .bodyValue("{}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("streaming.savepoint.unavailable");
    }

    @Test
    void stop_非long_running_拒绝() {
        UUID id = uuidv7(53);
        insert(id, 1, TASK_BATCH, "daily-batch", "RUNNING", false, null);

        client.post().uri("/api/ops/streaming-tasks/{id}/stop", id)
                .bodyValue("{}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("streaming.not_long_running");
    }

    static final String HANDLE = "{\"jobId\":\"job-1\",\"restEndpoint\":\"http://flink:8081\"}";

    void insertCheckpointWithId(UUID cpId, UUID instanceId, int ordinal, String status) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO task_checkpoint (id, task_instance_id, ordinal, checkpoint_path, status, "
                        + "completed_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                cpId, instanceId, ordinal, "hdfs:///sp/" + ordinal, status,
                "SUCCESS".equals(status) ? now : null, now);
    }

    @Test
    void checkpoints_列表ordinalDESC带resumable() {
        UUID id = uuidv7(61);
        insert(id, 1, TASK_A, "mysql-sync-realtime", "STOPPED", true, null);
        insertCheckpoint(id, 1, "SUCCESS");
        insertCheckpoint(id, 2, "SUCCESS");

        client.get().uri("/api/ops/streaming-tasks/{id}/checkpoints", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].ordinal").isEqualTo(2)
                .jsonPath("$.data[0].resumable").isEqualTo(true)
                .jsonPath("$.data[1].ordinal").isEqualTo(1);
    }

    @Test
    void resume_有效检查点_置WAITING保留句柄不动attempt() {
        UUID id = uuidv7(62);
        UUID cpId = UUID.fromString("00000000-0000-7000-8000-620100000062");
        insert(id, 1, TASK_A, "mysql-sync-realtime", "STOPPED", true, HANDLE);
        insertCheckpointWithId(cpId, id, 3, "SUCCESS");

        client.post().uri("/api/ops/streaming-tasks/{id}/resume", id)
                .bodyValue("{\"checkpointId\":\"" + cpId + "\"}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);

        var row = jdbc.queryForMap(
                "SELECT state, resume_checkpoint_id, external_job_handle, attempt FROM task_instance WHERE id=?", id);
        org.assertj.core.api.Assertions.assertThat(row.get("state")).isEqualTo("WAITING");
        org.assertj.core.api.Assertions.assertThat(row.get("resume_checkpoint_id").toString()).isEqualTo(cpId.toString());
        org.assertj.core.api.Assertions.assertThat(row.get("external_job_handle")).isEqualTo(HANDLE);  // reattach 保留
        org.assertj.core.api.Assertions.assertThat(((Number) row.get("attempt")).intValue()).isEqualTo(0);  // 060 栅栏不动
    }

    @Test
    void resume_无效检查点_invalid() {
        UUID id = uuidv7(63);
        insert(id, 1, TASK_A, "mysql-sync-realtime", "STOPPED", true, HANDLE);

        client.post().uri("/api/ops/streaming-tasks/{id}/resume", id)
                .bodyValue("{\"checkpointId\":\"00000000-0000-7000-8000-000000009999\"}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("streaming.checkpoint.invalid");
    }

    @Test
    void unauthenticated_401() {
        WebTestClient noAuth = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        noAuth.get().uri("/api/ops/streaming-tasks")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
