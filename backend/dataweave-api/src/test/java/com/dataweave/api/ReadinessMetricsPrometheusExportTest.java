package com.dataweave.api;

import com.dataweave.master.application.SchedulerMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F4 复现/回归：readiness 六指标必须能在真正的 PrometheusMeterRegistry scrape 输出里出现。
 * SimpleMeterRegistry（master 单测）校验宽松，PrometheusMeterRegistry 更严格——用它精确镜像
 * actuator /actuator/prometheus 的导出路径，定位/守卫 "注册了却不暴露" 缺陷。
 */
class ReadinessMetricsPrometheusExportTest {

    @Test
    void readiness六指标出现在prometheus_scrape() {
        PrometheusMeterRegistry reg = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        SchedulerMetrics m = new SchedulerMetrics(reg, mock(JdbcTemplate.class));
        // 触发一次埋点，确保 histogram/counter 有样本
        m.recordReadinessSignalLag(Duration.ofMillis(120));
        m.markReadinessMaintainBatch(3);
        m.setReadinessSignalPending(5);
        m.markReadinessDriftCorrected(1);
        m.recordReadinessRecomputeScope(4);

        String scrape = reg.scrape();

        // 对照：dispatch/claim/slot 应出现（真跑中确认暴露）
        assertThat(scrape).as("对照 dw_dispatch").contains("dw_dispatch");
        assertThat(scrape).as("对照 dw_claim").contains("dw_claim");
        // 待守卫：readiness 六指标
        assertThat(scrape).as("dw_readiness_signal_lag").contains("dw_readiness_signal_lag");
        assertThat(scrape).as("dw_readiness_maintain_batch").contains("dw_readiness_maintain_batch");
        assertThat(scrape).as("dw_readiness_signal_pending").contains("dw_readiness_signal_pending");
        assertThat(scrape).as("dw_readiness_drift_corrected").contains("dw_readiness_drift_corrected");
        assertThat(scrape).as("dw_readiness_recompute_scope").contains("dw_readiness_recompute_scope");
        assertThat(scrape).as("dw_readiness_unmet_ready_candidates").contains("dw_readiness_unmet_ready_candidates");
    }
}
