package com.dataweave.master.application;

import com.dataweave.master.domain.AtomicMetric;
import com.dataweave.master.domain.AtomicMetricRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 血缘服务：按指标 code 查最新版本 AtomicMetric，再经 neo4j 查指标血缘（{@code COMPUTED_FROM}）。
 * 迁移自 PG {@code metric_lineage} 表（A2）；neo4j 不可达时友好降级为 empty。
 *
 * <p>036 FR-013：移除硬编码 {@code 1L,1L}，{@code (tenantId, projectId)} 由上层（MCP / API）
 * 从请求上下文解析后传入；查询按项目作用域，与 {@link LineageQueryService} 全量方法签名一致。
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

    /**
     * 按 code 找当前项目最新版本 AtomicMetric，再取其 neo4j 指标血缘；不可达降级为 empty。
     *
     * @param tenantId  当前请求租户（由上层从 TenantContext 传入，master 不直接读上下文）
     * @param projectId 当前请求项目（同上）
     * @param code      指标 code
     */
    public Optional<LineagePath> lineageOf(Long tenantId, Long projectId, String code) {
        Optional<AtomicMetric> metric =
                atomicMetricRepository.findFirstByProjectIdAndCodeOrderByVersionNoDesc(projectId, code);
        if (metric.isEmpty()) {
            return Optional.empty();
        }
        AtomicMetric m = metric.get();
        try {
            com.dataweave.master.lineage.MetricLineage lineage =
                    lineageQueryService.metricLineage(tenantId, projectId, "ATOMIC", m.getId());
            return Optional.of(new LineagePath(m, lineage));
        } catch (Exception e) {
            // neo4j 不可达：MCP query_lineage 工具友好降级（不抛 503），返回 empty
            return Optional.empty();
        }
    }
}
