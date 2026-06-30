package com.dataweave.master.application.asset;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 资产目录 + 指标市场读模型 DTO 集合（应用层 → 接口层）。
 * 血缘/质量徽章「懒加载 + 可降级」，故独立 view，详情主体不内联。
 */
public final class AssetDtos {

    private AssetDtos() {
    }

    /** 分面搜索查询条件（关键词 + 多分面叠加，有界分页）。 */
    public record SearchQuery(String keyword, String type, String owner, String tag,
                              String sensitivity, Double qualityMin, int page, int size) {
    }

    /** 搜索结果项（列表态）。 */
    public record AssetSummary(Long id, Long datasourceId, String qualifiedName, String name,
                               Long ownerId, String sensitivity, String status, List<String> tags) {
    }

    /** 分面搜索结果：有界分页 + 分面计数 + 截断标记。 */
    public record SearchResult(List<AssetSummary> items, long total,
                               Map<String, Map<String, Long>> facets, boolean truncated) {
    }

    /** 资产详情（元数据主体；血缘/质量徽章经独立端点懒加载）。 */
    public record AssetDetail(Long id, Long datasourceId, String qualifiedName, String name,
                              String description, Long ownerId, Long stewardId,
                              List<String> glossaryTerms, String sensitivity,
                              String schemaSnapshotJson, String lineageTableRef,
                              String status, List<String> tags,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 资产血缘入口（消费 LineageQueryService；neo4j 不可达则 degraded）。 */
    public record LineageEntryView(boolean available, boolean degraded, String degradeReason,
                                   String tableRef, int upstreamCount, int downstreamCount) {

        public static LineageEntryView degraded(String reason, String tableRef) {
            return new LineageEntryView(false, true, reason, tableRef, 0, 0);
        }

        public static LineageEntryView ok(String tableRef, int up, int down) {
            return new LineageEntryView(true, false, null, tableRef, up, down);
        }

        public static LineageEntryView none(String tableRef) {
            return new LineageEntryView(false, false, null, tableRef, 0, 0);
        }
    }

    /** 质量徽章（消费份2 quality_scorecard；不可达则 degraded）。 */
    public record QualityBadgeView(boolean available, boolean degraded, Double score, String grade) {

        public static QualityBadgeView unavailable() {
            return new QualityBadgeView(false, true, null, null);
        }

        public static QualityBadgeView ok(Double score, String grade) {
            return new QualityBadgeView(true, false, score, grade);
        }
    }

    // ─── 指标市场 ───────────────────────────────────────────────

    public record ListingSummary(Long id, String metricType, Long metricId, String metricCode,
                                 Long ownerId, String certification, String status, String freshnessInfo) {
    }

    public record ListingSearchResult(List<ListingSummary> items, long total,
                                      Map<String, Map<String, Long>> facets, boolean truncated) {
    }

    /** 指标详情：上架字段 + 复用现有 metric 定义（degraded 安全）+ 认证状态。 */
    public record ListingDetail(Long id, String metricType, Long metricId, String metricCode,
                                Long ownerId, String certification, Long certifiedBy,
                                LocalDateTime certifiedAt, String freshnessInfo, String description,
                                String status, Map<String, Object> definition, int reuseCount) {
    }
}
