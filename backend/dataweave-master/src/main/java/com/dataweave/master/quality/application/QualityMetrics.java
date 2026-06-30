package com.dataweave.master.quality.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 质量 Micrometer 仪表（FR-012，镜像 {@code SchedulerMetrics} 范式）。
 * 指标定义不可变（改加 version，不 UPDATE）。
 *
 * <p>暴露途径：{@code /actuator/prometheus} + {@code /api/ops/metrics}。
 */
@Component
public class QualityMetrics {

    private final Timer checkLatency;
    private final Counter resultPass, resultFail, resultWarn, resultError;
    private final Counter blockCount;
    private final Counter signalCount;

    public QualityMetrics(MeterRegistry registry) {
        this.checkLatency = Timer.builder("quality.check.latency")
                .description("质量检查执行耗时")
                .register(registry);
        this.resultPass = Counter.builder("quality.result.count")
                .tag("status", "PASS")
                .description("通过断言数")
                .register(registry);
        this.resultFail = Counter.builder("quality.result.count")
                .tag("status", "FAIL")
                .description("失败断言数")
                .register(registry);
        this.resultWarn = Counter.builder("quality.result.count")
                .tag("status", "WARN")
                .description("告警断言数")
                .register(registry);
        this.resultError = Counter.builder("quality.result.count")
                .tag("status", "ERROR")
                .description("基础设施失败断言数（不发信号/不阻断/不计分）")
                .register(registry);
        this.blockCount = Counter.builder("quality.block.count")
                .description("BLOCK 阻断下游次数")
                .register(registry);
        this.signalCount = Counter.builder("quality.signal.count")
                .description("发出的 QUALITY_FAILED 信号数")
                .register(registry);
    }

    public void recordCheckLatency(long ms) {
        checkLatency.record(ms, TimeUnit.MILLISECONDS);
    }

    public void recordResult(String status) {
        switch (status) {
            case "PASS" -> resultPass.increment();
            case "FAIL" -> resultFail.increment();
            case "WARN" -> resultWarn.increment();
            case "ERROR" -> resultError.increment();
        }
    }

    public void recordBlock() { blockCount.increment(); }
    public void recordSignal() { signalCount.increment(); }
}
