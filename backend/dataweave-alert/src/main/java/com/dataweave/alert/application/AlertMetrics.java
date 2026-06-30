package com.dataweave.alert.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Alert engine Micrometer metrics（镜像 {@code SchedulerMetrics} 范式）。
 *
 * <p>暴露经 {@code /actuator/prometheus} 和 {@code /api/ops/metrics}。
 */
@Component
public class AlertMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();

    public AlertMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记录一次告警评估延迟 */
    public void recordEvalLatency(long millis) {
        timer("alert.eval.latency").record(millis, TimeUnit.MILLISECONDS);
    }

    /** 记录一条告警触发（按 severity） */
    public void markFired(String severity) {
        counter("alert.fired.count", "severity", severity).increment();
    }

    /** 记录一条通知投递（按 channel 类型 + status） */
    public void markNotify(String channelType, String status) {
        counter("alert.notify.count", "channel", channelType, "status", status).increment();
    }

    /** 记录一次通知重试 */
    public void markRetry() {
        counter("alert.notify.retry.count").increment();
    }

    private Counter counter(String name, String... tags) {
        String key = name + String.join(",", tags);
        return counters.computeIfAbsent(key, k -> {
            var builder = Counter.builder(name);
            for (int i = 0; i < tags.length; i += 2) {
                builder = builder.tag(tags[i], tags[i + 1]);
            }
            return builder.register(registry);
        });
    }

    private Timer timer(String name, String... tags) {
        String key = name + String.join(",", tags);
        return timers.computeIfAbsent(key, k -> {
            var builder = Timer.builder(name);
            for (int i = 0; i < tags.length; i += 2) {
                builder = builder.tag(tags[i], tags[i + 1]);
            }
            return builder.register(registry);
        });
    }
}
