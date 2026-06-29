package com.dataweave.master.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
}
