package com.dataweave.master.application.asset;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 资产目录 + 指标市场可观测指标（FR-011）。Micrometer 注册，经 actuator + {@code /api/ops/metrics} 暴露。
 *
 * <ul>
 *   <li>{@code catalog.search.count} / {@code catalog.search.latency} — 搜索 QPS / 延迟</li>
 *   <li>{@code catalog.write.count} — 资产/上架/订阅/认证写计数（tag: action）</li>
 *   <li>{@code catalog.lineage.degraded} — 血缘消费降级命中（SC-002 可观测）</li>
 *   <li>{@code catalog.quality.degraded} — 质量徽章消费降级命中</li>
 *   <li>{@code catalog.asset_changed.published} — ASSET_CHANGED 信号发射计数（喂 021）</li>
 * </ul>
 */
@Service
public class CatalogMetrics {

    private final MeterRegistry registry;
    private final Timer searchLatency;
    private final Counter searchCount;
    private final Counter lineageDegraded;
    private final Counter qualityDegraded;
    private final Counter assetChangedPublished;

    public CatalogMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.searchLatency = Timer.builder("catalog.search.latency")
                .description("分面搜索延迟分布").register(registry);
        this.searchCount = Counter.builder("catalog.search.count")
                .description("分面搜索次数").register(registry);
        this.lineageDegraded = Counter.builder("catalog.lineage.degraded")
                .description("血缘消费降级命中数").register(registry);
        this.qualityDegraded = Counter.builder("catalog.quality.degraded")
                .description("质量徽章消费降级命中数").register(registry);
        this.assetChangedPublished = Counter.builder("catalog.asset_changed.published")
                .description("ASSET_CHANGED 信号发射数").register(registry);
    }

    public void recordSearch(long nanos) {
        searchCount.increment();
        searchLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordWrite(String action) {
        Counter.builder("catalog.write.count").tag("action", action == null ? "unknown" : action)
                .description("资产/上架/订阅/认证写计数").register(registry).increment();
    }

    public void recordLineageDegraded() {
        lineageDegraded.increment();
    }

    public void recordQualityDegraded() {
        qualityDegraded.increment();
    }

    public void recordAssetChanged() {
        assetChangedPublished.increment();
    }
}
