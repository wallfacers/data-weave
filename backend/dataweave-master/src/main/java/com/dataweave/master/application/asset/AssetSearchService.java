package com.dataweave.master.application.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分面搜索（FR-003 / SC-003）：关键词命中 name/description/qualifiedName/glossary + 分面叠加过滤，
 * 有界分页 + 分面计数 + 截断标记。双方言（H2/PG）：LIKE 用 {@code CONCAT} 不用 {@code ||}。
 *
 * <h3>敏感度可见性（SC-006）</h3>
 * 跨租户/项目硬隔离；PII 资产仅 owner/steward 可见（不可搜出、不计入分面）。
 *
 * <h3>有界（不拉爆）</h3>
 * 单页 size 上界 {@link #MAX_PAGE_SIZE}；总命中超 {@link #HARD_CAP} → {@code truncated=true} + log.warn（不静默）。
 */
@Service
public class AssetSearchService {

    private static final Logger log = LoggerFactory.getLogger(AssetSearchService.class);

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int HARD_CAP = 2000;

    private final JdbcTemplate jdbc;
    private final CatalogMetrics metrics;

    public AssetSearchService(JdbcTemplate jdbc, CatalogMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /** 累积 WHERE 子句 + 参数（count/items/facet 复用同一过滤）。 */
    private static final class Where {
        final StringBuilder sql = new StringBuilder();
        final List<Object> args = new ArrayList<>();

        void and(String clause, Object... a) {
            sql.append(" AND ").append(clause);
            for (Object x : a) args.add(x);
        }

        Object[] args() {
            return args.toArray();
        }
    }

    private Where build(long tenantId, long projectId, Long callerUserId, AssetDtos.SearchQuery q) {
        Where w = new Where();
        // 硬隔离 + 排除下线
        w.sql.append("FROM data_asset a WHERE a.tenant_id = ? AND a.project_id = ? AND a.deleted = 0 AND a.status <> 'RETIRED'");
        w.args.add(tenantId);
        w.args.add(projectId);

        // 敏感度可见性：PII 仅 owner/steward 可见
        w.and("(a.sensitivity <> 'PII' OR a.owner_id = ? OR a.steward_id = ?)", callerUserId, callerUserId);

        if (q.keyword() != null && !q.keyword().isBlank()) {
            String kw = q.keyword().trim().toLowerCase();
            w.and("(LOWER(a.name) LIKE CONCAT('%', ?, '%') OR LOWER(a.description) LIKE CONCAT('%', ?, '%') "
                    + "OR LOWER(a.qualified_name) LIKE CONCAT('%', ?, '%') OR LOWER(a.glossary_terms) LIKE CONCAT('%', ?, '%'))",
                    kw, kw, kw, kw);
        }
        if (q.owner() != null && !q.owner().isBlank()) {
            Long ownerId = parseLong(q.owner());
            if (ownerId != null) {
                w.and("a.owner_id = ?", ownerId);
            }
        }
        if (q.sensitivity() != null && !q.sensitivity().isBlank()) {
            w.and("a.sensitivity = ?", q.sensitivity().trim().toUpperCase());
        }
        if (q.tag() != null && !q.tag().isBlank()) {
            w.and("EXISTS (SELECT 1 FROM entity_tag et JOIN tag t ON et.tag_id = t.id "
                    + "WHERE et.entity_type = 'ASSET' AND et.entity_id = a.id AND t.name = ?)", q.tag().trim());
        }
        // 注：qualityMin 需联份2 quality_scorecard（022 落地后启用）；当前缺表，v1 不施加该过滤（降级安全）。
        return w;
    }

    public AssetDtos.SearchResult search(long tenantId, long projectId, Long callerUserId, AssetDtos.SearchQuery q) {
        long t0 = System.nanoTime();
        try {
            Where w = build(tenantId, projectId, callerUserId, q);

            Long totalObj = jdbc.queryForObject("SELECT COUNT(*) " + w.sql, Long.class, w.args());
            long total = totalObj == null ? 0L : totalObj;
            boolean truncated = total > HARD_CAP;
            if (truncated) {
                log.warn("Asset search truncated: tenant={}, project={}, total={} > cap={}", tenantId, projectId, total, HARD_CAP);
            }

            int size = clampSize(q.size());
            int page = q.page() <= 0 ? 1 : q.page();
            int offset = (page - 1) * size;

            List<Object> pageArgs = new ArrayList<>(List.of(w.args()));
            pageArgs.add(size);
            pageArgs.add(offset);
            List<AssetDtos.AssetSummary> items = jdbc.query(
                    "SELECT a.id, a.datasource_id, a.qualified_name, a.name, a.owner_id, a.sensitivity, a.status "
                            + w.sql + " ORDER BY a.updated_at DESC, a.id DESC LIMIT ? OFFSET ?",
                    (rs, i) -> new AssetDtos.AssetSummary(
                            rs.getLong("id"), rs.getLong("datasource_id"), rs.getString("qualified_name"),
                            rs.getString("name"), (Long) rs.getObject("owner_id"), rs.getString("sensitivity"),
                            rs.getString("status"), tagsOf(rs.getLong("id"))),
                    pageArgs.toArray());

            Map<String, Map<String, Long>> facets = new LinkedHashMap<>();
            facets.put("sensitivity", facet(w, "a.sensitivity"));
            facets.put("status", facet(w, "a.status"));
            facets.put("owner", facet(w, "a.owner_id"));
            facets.put("tag", tagFacet(w));

            return new AssetDtos.SearchResult(items, total, facets, truncated);
        } finally {
            metrics.recordSearch(System.nanoTime() - t0);
        }
    }

    private Map<String, Long> facet(Where w, String dim) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT " + dim + " AS k, COUNT(*) AS c " + w.sql + " GROUP BY " + dim + " ORDER BY c DESC",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    Object k = rs.getObject("k");
                    out.put(k == null ? "" : String.valueOf(k), rs.getLong("c"));
                }, w.args());
        return out;
    }

    private Map<String, Long> tagFacet(Where w) {
        Map<String, Long> out = new LinkedHashMap<>();
        // 在同一过滤集上联标签计数
        String sql = "SELECT t.name AS k, COUNT(*) AS c "
                + w.sql.toString().replaceFirst("FROM data_asset a",
                "FROM data_asset a JOIN entity_tag et ON et.entity_type = 'ASSET' AND et.entity_id = a.id JOIN tag t ON et.tag_id = t.id")
                + " GROUP BY t.name ORDER BY c DESC";
        jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            out.put(rs.getString("k"), rs.getLong("c"));
        }, w.args());
        return out;
    }

    private List<String> tagsOf(long assetId) {
        return jdbc.query("SELECT t.name FROM entity_tag et JOIN tag t ON et.tag_id = t.id "
                        + "WHERE et.entity_type = 'ASSET' AND et.entity_id = ?",
                (rs, i) -> rs.getString(1), assetId);
    }

    private static int clampSize(int size) {
        if (size <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
