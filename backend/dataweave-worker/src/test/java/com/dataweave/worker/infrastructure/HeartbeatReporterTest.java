package com.dataweave.worker.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * worker 心跳上报的可达地址拼装：上报 {@code host:port}，让 master 的 distributed 下发能按端口
 * 区分同机多 worker。advertise-host 显式配置时优先用它，否则回退本机名。
 */
class HeartbeatReporterTest {

    @Test
    void advertiseHostConfigured_usedWithPort() {
        assertThat(HeartbeatReporter.buildAdvertisedHost("127.0.0.1", "fallback-host", 8200))
                .isEqualTo("127.0.0.1:8200");
    }

    @Test
    void advertiseHostBlank_fallsBackToLocalHostWithPort() {
        assertThat(HeartbeatReporter.buildAdvertisedHost("", "fallback-host", 8100))
                .isEqualTo("fallback-host:8100");
    }

    @Test
    void advertiseHostNull_fallsBackToLocalHostWithPort() {
        assertThat(HeartbeatReporter.buildAdvertisedHost(null, "fallback-host", 8100))
                .isEqualTo("fallback-host:8100");
    }

    @Test
    void advertiseHostAlreadyHasPort_notDoubled() {
        assertThat(HeartbeatReporter.buildAdvertisedHost("10.0.0.5:8300", "fallback-host", 8100))
                .isEqualTo("10.0.0.5:8300");
    }
}
