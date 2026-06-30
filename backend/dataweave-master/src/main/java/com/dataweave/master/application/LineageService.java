package com.dataweave.master.application;

import com.dataweave.master.domain.AtomicMetric;
import com.dataweave.master.domain.AtomicMetricRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 血缘服务：按指标 code 查最新版本 AtomicMetric，再经 neo4j 查指标血缘（{@code COMPUTED_FROM}）。
 * 迁移自 PG {@code metric_lineage} 表（A2）；neo4j 不可达时友好降级为 empty。
 */
@Service
public class LineageService {

    private final AtomicMetricRepository atomicMetricRepository;
    private final LineageQueryService lineageQueryService;

    public LineageService(AtomicMetricRepository atomicMetricRepository,
                          LineageQueryService lineageQueryService) {
        this.atomicMetricRepository = atomicMetricRepository;
        this.lineageQueryService = lineageQueryService;
    }

    public record LineagePath(AtomicMetric metric,
                              com.dataweave.master.lineage.MetricLineage lineage) {
    }

    /** 按 code 找最新版本 AtomicMetric，再取其 neo4j 指标血缘；不可达降级为 empty。 */
    public Optional<LineagePath> lineageOf(String code) {
        Optional<AtomicMetric> metric = atomicMetricRepository.findFirstByCodeOrderByVersionNoDesc(code);
        if (metric.isEmpty()) {
            return Optional.empty();
        }
        AtomicMetric m = metric.get();
        try {
            // tenantId/projectId 沿用现状 1L/1L 占位（与 TaskService.recordLineage 一致）
            com.dataweave.master.lineage.MetricLineage lineage =
                    lineageQueryService.metricLineage(1L, 1L, "ATOMIC", m.getId());
            return Optional.of(new LineagePath(m, lineage));
        } catch (Exception e) {
            // neo4j 不可达：MCP query_lineage 工具友好降级（不抛 503），返回 empty
            return Optional.empty();
        }
    }
}
