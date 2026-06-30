package com.dataweave.master.application.asset;

import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.lineage.GraphNodeView;
import com.dataweave.master.lineage.LineageGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 资产血缘入口装配：消费 {@link LineageQueryService}（018-020 neo4j 只读，不重算）。
 *
 * <p><b>硬约束（SC-002）</b>：neo4j 不可达时优雅降级（{@code degraded=true}），目录主功能 100% 不受影响。
 * 降级命中经 {@link CatalogMetrics} 可观测。
 */
@Component
public class AssetLineageAssembler {

    private static final Logger log = LoggerFactory.getLogger(AssetLineageAssembler.class);

    private final LineageQueryService lineageQueryService;
    private final CatalogMetrics metrics;

    public AssetLineageAssembler(LineageQueryService lineageQueryService, CatalogMetrics metrics) {
        this.lineageQueryService = lineageQueryService;
        this.metrics = metrics;
    }

    /**
     * 装配资产的血缘入口（上下游各 1 跳计数即可，详图前端按需再拉）。
     *
     * @param tableRef 资产 lineage_table_ref（喂 LineageQueryService 的 tableId）；空 → none。
     */
    public AssetDtos.LineageEntryView assemble(long tenantId, long projectId, String tableRef) {
        if (tableRef == null || tableRef.isBlank()) {
            return AssetDtos.LineageEntryView.none(tableRef);
        }
        try {
            LineageGraph up = lineageQueryService.upstream(tenantId, projectId, tableRef, 1, GraphNodeView.Granularity.TABLE);
            LineageGraph down = lineageQueryService.downstream(tenantId, projectId, tableRef, 1, GraphNodeView.Granularity.TABLE);
            return AssetDtos.LineageEntryView.ok(tableRef,
                    up.nodes() == null ? 0 : up.nodes().size(),
                    down.nodes() == null ? 0 : down.nodes().size());
        } catch (RuntimeException e) {
            // BizException("lineage.store_unavailable") 或任何 neo4j 异常 → 降级（不阻断目录主功能）。
            metrics.recordLineageDegraded();
            log.debug("Lineage degraded for asset tableRef={}: {}", tableRef, e.toString());
            return AssetDtos.LineageEntryView.degraded("catalog.lineage_degraded", tableRef);
        }
    }

    /**
     * 装配指标血缘入口（消费 {@link LineageQueryService#metricLineage}，neo4j 单一底座）。不可达 → 降级。
     *
     * @param metricType 指标类型（ATOMIC/DERIVED）—— 与 metricId 联合定位 neo4j Metric 节点
     * @param metricId   指标 id
     * @param label      展示用标识（指标 code，喂 LineageEntryView 标签）
     */
    public AssetDtos.LineageEntryView assembleMetric(long tenantId, long projectId,
                                                     String metricType, Long metricId, String label) {
        if (metricType == null || metricType.isBlank() || metricId == null) {
            return AssetDtos.LineageEntryView.none(label);
        }
        try {
            com.dataweave.master.lineage.MetricLineage ml =
                    lineageQueryService.metricLineage(tenantId, projectId, metricType, metricId);
            int sources = ml.sources() == null ? 0 : ml.sources().size();
            return AssetDtos.LineageEntryView.ok(label, sources, 0);
        } catch (RuntimeException e) {
            metrics.recordLineageDegraded();
            log.debug("Metric lineage degraded for metric {}:{}: {}", metricType, metricId, e.toString());
            return AssetDtos.LineageEntryView.degraded("catalog.lineage_degraded", label);
        }
    }
}
