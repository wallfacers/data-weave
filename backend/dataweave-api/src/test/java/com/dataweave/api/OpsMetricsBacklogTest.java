package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import com.dataweave.master.domain.LogBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/ops/metrics 端点指标字段契约（第 3 层接线回归）：
 * <ul>
 *   <li>logStreamBacklog 经 {@code OpsController.metrics()} 刷新（{@code logBus.totalBacklog()}）后反映真实积压；</li>
 *   <li>4 个曾恒为 0 的字段（dispatchLatencyCount/leaseReclaims/sseConnections/logStreamBacklog）在响应中存在。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class OpsMetricsBacklogTest {

    @LocalServerPort
    int port;
    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    LogBus logBus;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
    }

    @Test
    void metrics端点_四个曾零字段均存在() {
        client.get().uri("/api/ops/metrics")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.dispatchLatencyCount").exists()
                .jsonPath("$.data.leaseReclaims").exists()
                .jsonPath("$.data.sseConnections").exists()
                .jsonPath("$.data.logStreamBacklog").exists();
    }

    @Test
    void metrics端点_logStreamBacklog随append增长() {
        // H2 profile 默认 InMemoryLogBus（共享 bean）；用 before/after 容忍其他用例的并行 append。
        long before = fetchLogStreamBacklog();
        UUID id = UUID.randomUUID();
        logBus.append(id, "l1");
        logBus.append(id, "l2");
        logBus.append(id, "l3");
        long after = fetchLogStreamBacklog();
        assertThat(after).isGreaterThanOrEqualTo(before + 3);
    }

    private long fetchLogStreamBacklog() {
        long[] holder = new long[1];
        client.get().uri("/api/ops/metrics")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.logStreamBacklog")
                .value(v -> holder[0] = ((Number) v).longValue());
        return holder[0];
    }
}
