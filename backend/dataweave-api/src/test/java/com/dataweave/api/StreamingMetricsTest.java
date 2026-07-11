package com.dataweave.api;

import com.dataweave.master.application.SchedulerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 062 US5 实时任务运维 gauge：scheduler.streaming.checkpoint.total（成功检查点累计）
 * + scheduler.streaming.recovering（续跑中 long_running 实例）经 refreshStreamingGauges 反映真实。
 * before/after delta 容忍共享 H2 的并行数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class StreamingMetricsTest {

    @Autowired
    SchedulerMetrics metrics;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MeterRegistry registry;

    static final long TASK = 629001L;

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    void streamingGauges_反映检查点与续跑中实例() {
        metrics.refreshStreamingGauges();
        double cpBefore = gauge("scheduler.streaming.checkpoint.total");
        double recBefore = gauge("scheduler.streaming.recovering");

        LocalDateTime now = LocalDateTime.now();
        UUID recovering = UUID.randomUUID();
        UUID cpId = UUID.randomUUID();
        // 续跑中实例：long_running + WAITING + resume_checkpoint_id 非空
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, task_id, run_mode, state, "
                        + "long_running, resume_checkpoint_id, attempt, created_at, updated_at, deleted, version) "
                        + "VALUES (?,1,1,?, 'NORMAL','WAITING', TRUE, ?, 0, ?, ?, 0, 0)",
                recovering, TASK, cpId, now, now);
        // 2 个 SUCCESS 检查点
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO task_checkpoint (id, task_instance_id, ordinal, checkpoint_path, status, "
                            + "completed_at, created_at) VALUES (?,?,?,?, 'SUCCESS', ?, ?)",
                    UUID.randomUUID(), recovering, i, "hdfs:///sp/" + i, now, now);
        }

        metrics.refreshStreamingGauges();

        assertThat(gauge("scheduler.streaming.checkpoint.total")).isGreaterThanOrEqualTo(cpBefore + 2);
        assertThat(gauge("scheduler.streaming.recovering")).isGreaterThanOrEqualTo(recBefore + 1);

        // 清理
        jdbc.update("DELETE FROM task_checkpoint WHERE task_instance_id=?", recovering);
        jdbc.update("DELETE FROM task_instance WHERE id=?", recovering);
    }
}
