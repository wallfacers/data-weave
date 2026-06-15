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
        assertThat(HeartbeatReporter.buildAdvertisedHost("127.0.0.1", "fallback-host", 8082))
                .isEqualTo("127.0.0.1:8082");
    }

    @Test
    void advertiseHostBlank_fallsBackToLocalHostWithPort() {
        assertThat(HeartbeatReporter.buildAdvertisedHost("", "fallback-host", 8081))
                .isEqualTo("fallback-host:8081");
    }

    @Test
    void advertiseHostNull_fallsBackToLocalHostWithPort() {
        assertThat(HeartbeatReporter.buildAdvertisedHost(null, "fallback-host", 8081))
                .isEqualTo("fallback-host:8081");
    }

    @Test
    void advertiseHostAlreadyHasPort_notDoubled() {
        assertThat(HeartbeatReporter.buildAdvertisedHost("10.0.0.5:9090", "fallback-host", 8081))
                .isEqualTo("10.0.0.5:9090");
    }
}
