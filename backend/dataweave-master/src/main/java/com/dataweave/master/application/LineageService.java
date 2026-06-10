package com.dataweave.master.application;

import com.dataweave.master.domain.AtomicMetric;
import com.dataweave.master.domain.AtomicMetricRepository;
import com.dataweave.master.domain.MetricLineage;
import com.dataweave.master.domain.MetricLineageRepository;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 血缘服务：按指标 code 查最新版本，再查 metric_lineage 返回完整血缘路径。
 */
@Service
public class LineageService {

    private final AtomicMetricRepository atomicMetricRepository;
    private final MetricLineageRepository metricLineageRepository;

    public LineageService(AtomicMetricRepository atomicMetricRepository,
                          MetricLineageRepository metricLineageRepository) {
        this.atomicMetricRepository = atomicMetricRepository;
        this.metricLineageRepository = metricLineageRepository;
    }

    public record LineagePath(AtomicMetric metric, List<MetricLineage> edges) {
    }

    /** 按 code 找最新版本 AtomicMetric，再取其所有下游血缘边。 */
    public Optional<LineagePath> lineageOf(String code) {
        Optional<AtomicMetric> metric = atomicMetricRepository.findFirstByCodeOrderByVersionNoDesc(code);
        if (metric.isEmpty()) {
            return Optional.empty();
        }
        AtomicMetric m = metric.get();
        List<MetricLineage> edges = metricLineageRepository
                .findByMetricTypeAndMetricId("ATOMIC", m.getId());
        return Optional.of(new LineagePath(m, edges == null ? Collections.emptyList() : edges));
    }
}
