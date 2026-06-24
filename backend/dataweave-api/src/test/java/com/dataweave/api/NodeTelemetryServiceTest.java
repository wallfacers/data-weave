package com.dataweave.api;

import com.dataweave.master.application.NodeTelemetryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 真采集（live-telemetry）：master 端按 worker_node_code 聚合真实并发数 + 近 7 天失败 history。
 * 用唯一 node code 隔离，计数与种子/其他测试无关。h2 零依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class NodeTelemetryServiceTest {

    private static final String NODE = "node-fi-test";
    private static final long TASK = 99001L;

    @Autowired
    private NodeTelemetryService telemetry;

    @Autowired
    private JdbcTemplate jdbc;

    private void insertInstance(String state, LocalDateTime finishedAt) {
        jdbc.update(
                "INSERT INTO task_instance (id, tenant_id, project_id, task_id, run_mode, state, attempt, "
                        + "worker_node_code, started_at, finished_at, created_at, updated_at, deleted, version) "
                        + "VALUES (?,1,1,?, 'NORMAL', ?, 1, ?, ?, ?, ?, ?, 0, 0)",
                UUID.randomUUID(), TASK, state, NODE,
                LocalDateTime.now(), finishedAt, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void aggregatesRealConcurrentAndFailureHistory() {
        // 2 个在跑（RUNNING + DISPATCHED）→ concurrentTasks=2
        insertInstance("RUNNING", null);
        insertInstance("DISPATCHED", null);
        // 近 7 天 1 个 FAILED + 1 个 8 天前 FAILED（超窗，不计）
        insertInstance("FAILED", LocalDateTime.now().minusDays(1));
        insertInstance("FAILED", LocalDateTime.now().minusDays(8));

        assertThat(telemetry.concurrentTasks(NODE)).isEqualTo(2);
        assertThat(telemetry.failureCount7d(TASK, NODE)).isEqualTo(1);

        // 空入参防御
        assertThat(telemetry.concurrentTasks(null)).isZero();
        assertThat(telemetry.failureCount7d(null, NODE)).isZero();
    }
}
