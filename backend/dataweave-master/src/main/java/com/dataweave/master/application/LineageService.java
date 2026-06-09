package com.dataweave.master.application;

import com.dataweave.master.domain.Metric;
import com.dataweave.master.domain.MetricLineage;
import com.dataweave.master.domain.MetricLineageRepository;
import com.dataweave.master.domain.MetricRepository;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 血缘服务：查 metric_lineage 返回「指标 -> SQL -> 物理表」链路。
 */
@Service
public class LineageService {

    private final MetricRepository metricRepository;
    private final MetricLineageRepository lineageRepository;

    public LineageService(MetricRepository metricRepository, MetricLineageRepository lineageRepository) {
        this.metricRepository = metricRepository;
        this.lineageRepository = lineageRepository;
    }

    public record LineagePath(Metric metric, List<MetricLineage> edges) {
    }

    public Optional<LineagePath> lineageOf(String metricName) {
        Optional<Metric> metric = metricRepository.findLatestByName(metricName);
        if (metric.isEmpty()) {
            return Optional.empty();
        }
        List<MetricLineage> edges = lineageRepository.findByMetricId(metric.get().getId());
        return Optional.of(new LineagePath(metric.get(), edges == null ? Collections.emptyList() : edges));
    }
}
