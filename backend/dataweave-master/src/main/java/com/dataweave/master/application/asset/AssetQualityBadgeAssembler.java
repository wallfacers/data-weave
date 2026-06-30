package com.dataweave.master.application.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 质量徽章装配：消费份2（022 数据质量）{@code quality_scorecard}（不重算质量）。
 *
 * <p><b>降级（SC-002）</b>：022 未落地（表不存在）或不可达时优雅降级（{@code degraded=true}，隐藏徽章），
 * 目录主功能不受影响。023 独立可编译可测——不硬依赖份2 的类，仅按表名只读探查，缺表即降级。
 */
@Component
public class AssetQualityBadgeAssembler {

    private static final Logger log = LoggerFactory.getLogger(AssetQualityBadgeAssembler.class);

    private final JdbcTemplate jdbc;
    private final CatalogMetrics metrics;

    public AssetQualityBadgeAssembler(JdbcTemplate jdbc, CatalogMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /**
     * 取资产对应表的最新质量评分徽章。
     *
     * @param targetRef 资产 qualified_name（份2 评分卡的目标键，按表维度）
     */
    public AssetDtos.QualityBadgeView assemble(long tenantId, long projectId, String targetRef) {
        if (targetRef == null || targetRef.isBlank()) {
            return AssetDtos.QualityBadgeView.unavailable();
        }
        try {
            // 份2 quality_scorecard 的目标维度键名以 022 落地为准；此处按 (tenant, project, target_ref) 只读探查，
            // 任一不符（表不存在 / 列不符）→ DataAccessException → 降级。
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT score, grade FROM quality_scorecard "
                            + "WHERE tenant_id = ? AND project_id = ? AND target_ref = ? "
                            + "ORDER BY updated_at DESC",
                    tenantId, projectId, targetRef);
            if (rows.isEmpty()) {
                // 表存在但该资产无评分 → 非降级，仅「暂无质量分」
                return new AssetDtos.QualityBadgeView(true, false, null, null);
            }
            Map<String, Object> row = rows.get(0);
            Double score = row.get("score") instanceof Number n ? n.doubleValue() : null;
            String grade = row.get("grade") == null ? null : String.valueOf(row.get("grade"));
            return AssetDtos.QualityBadgeView.ok(score, grade);
        } catch (DataAccessException e) {
            metrics.recordQualityDegraded();
            log.debug("Quality badge degraded for targetRef={} (份2 未落地或不可达): {}", targetRef, e.toString());
            return AssetDtos.QualityBadgeView.unavailable();
        }
    }
}
