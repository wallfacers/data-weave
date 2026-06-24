package com.dataweave.worker.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 真采集（live-telemetry）：HeartbeatReporter.sample() 输出真实 0-100 百分比，而非旧硬编码常量。
 */
class HeartbeatMetricsTest {

    @Test
    void sample_returnsRealPercentages_notHardcodedConstants() {
        HeartbeatReporter.NodeMetrics m = HeartbeatReporter.sample();

        // 量纲 0-100
        assertThat(m.cpu()).isBetween(0.0, 100.0);
        assertThat(m.mem()).isBetween(0.0, 100.0);
        assertThat(m.disk()).isBetween(0.0, 100.0);
        assertThat(m.loadAvg()).isGreaterThanOrEqualTo(0.0);

        // 真实机器内存/磁盘占用必 > 0，证明是真采集而非旧的 0.45/0.5 常量
        assertThat(m.mem()).isGreaterThan(0.0);
        assertThat(m.disk()).isGreaterThan(0.0);

        // 不是旧的硬编码 fraction 常量（cpu=0.3, mem=0.45, disk=0.5, loadAvg=1.2 同时出现）
        boolean allLegacy = m.cpu() == 0.3 && m.mem() == 0.45 && m.disk() == 0.5 && m.loadAvg() == 1.2;
        assertThat(allLegacy).isFalse();
    }
}
