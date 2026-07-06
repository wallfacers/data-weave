package com.dataweave.master.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证 {@link SchedulerMetrics} 四个「曾零调用」埋点（dispatchLatency / leaseReclaim / sseConnections /
 * logStreamBacklog）经 record/mark/set 后能正确反映到 {@link SchedulerMetrics.MetricsSnapshot}。
 *
 * <p>用 {@link SimpleMeterRegistry}（master 模块无 actuator，micrometer-core 自带），不依赖 Spring 上下文。
 */
class SchedulerMetricsTest {

    private SchedulerMetrics newMetrics() {
        return new SchedulerMetrics(new SimpleMeterRegistry(), mock(JdbcTemplate.class));
    }

    @Test
    void recordDispatchLatency_累积计数与均值() {
        SchedulerMetrics m = newMetrics();
        m.recordDispatchLatency(Duration.ofMillis(10));
        m.recordDispatchLatency(Duration.ofMillis(30));
        var snap = m.snapshot();
        assertThat(snap.dispatchLatencyCount).isEqualTo(2);
        // 均值 = (10+30)/2 = 20ms
        assertThat(snap.dispatchLatencyMean).isEqualTo(20.0);
    }

    @Test
    void markLeaseReclaim_递增计数() {
        SchedulerMetrics m = newMetrics();
        m.markLeaseReclaim();
        m.markLeaseReclaim();
        m.markLeaseReclaim();
        assertThat(m.snapshot().leaseReclaims).isEqualTo(3);
    }

    @Test
    void setSseConnections_与setLogStreamBacklog_反映到快照() {
        SchedulerMetrics m = newMetrics();
        m.setSseConnections(2);
        m.setLogStreamBacklog(42);
        var snap = m.snapshot();
        assertThat(snap.sseConnections).isEqualTo(2);
        assertThat(snap.logStreamBacklog).isEqualTo(42);
    }

    @Test
    void 初始快照四个字段为零() {
        var snap = newMetrics().snapshot();
        assertThat(snap.dispatchLatencyCount).isZero();
        assertThat(snap.leaseReclaims).isZero();
        assertThat(snap.sseConnections).isZero();
        assertThat(snap.logStreamBacklog).isZero();
    }

    // ─── US3 收尾：周期采样器驱动第 2 层 slot gauge（此前仅 /api/ops/metrics 按需刷新，
    //     Prometheus 抓取恒见陈旧 0，US3 slot_util 压测正踩此坑）───

    @Test
    void sampleGauges_驱动slot利用率gauge在prometheus路径可读() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("test-metrics-sample-" + System.currentTimeMillis())
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE task_instance (id UUID PRIMARY KEY, state VARCHAR(32), deleted SMALLINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE worker_nodes (id BIGINT PRIMARY KEY, status VARCHAR(32), " +
                "max_concurrent_tasks INTEGER, deleted SMALLINT DEFAULT 0)");
        // used = 3 (RUNNING/DISPATCHED)，total = 10（两个 ONLINE worker 各 5）→ 利用率 0.3
        jdbc.update("INSERT INTO task_instance (id, state, deleted) VALUES (RANDOM_UUID(),'RUNNING',0)");
        jdbc.update("INSERT INTO task_instance (id, state, deleted) VALUES (RANDOM_UUID(),'RUNNING',0)");
        jdbc.update("INSERT INTO task_instance (id, state, deleted) VALUES (RANDOM_UUID(),'DISPATCHED',0)");
        jdbc.update("INSERT INTO task_instance (id, state, deleted) VALUES (RANDOM_UUID(),'WAITING',0)"); // 不计
        jdbc.update("INSERT INTO worker_nodes (id, status, max_concurrent_tasks, deleted) VALUES (1,'ONLINE',5,0)");
        jdbc.update("INSERT INTO worker_nodes (id, status, max_concurrent_tasks, deleted) VALUES (2,'ONLINE',5,0)");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SchedulerMetrics m = new SchedulerMetrics(registry, jdbc);

        // 采样前：gauge 恒初值 0（复现 US3 踩坑现象）
        assertThat(registry.get("scheduler.slot.utilization").gauge().value())
                .as("采样前 gauge 为陈旧初值").isEqualTo(0.0);

        m.sampleGauges();

        // 采样后：Prometheus 抓取路径（gauge 直读）即得活值 0.3
        assertThat(registry.get("scheduler.slot.utilization").gauge().value())
                .as("采样器驱动后 slot 利用率 gauge 可读").isEqualTo(0.3);
        // /api/ops/metrics 快照同源，一致
        assertThat(m.snapshot().slotUtilization).isEqualTo(0.3);
    }
}
