package com.dataweave.master.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 数据新鲜度聚合查询。
 *
 * <p>按任务聚合最近一次成功执行的时间（MAX(finished_at) WHERE state=SUCCESS），
 * 分档为 FRESH（≤6h）/ AGING（&gt;6h）/ STALE（&gt;24h）/ NEVER（从未成功）。
 *
 * <p>SQL 使用 EXTRACT(EPOCH FROM ...) 兼容 H2（PostgreSQL 兼容模式）与真实 PG。
 */
@Service
public class FreshnessService {

    private static final double FRESH_HOURS = 6.0;
    private static final double STALE_HOURS = 24.0;

    private static final Set<String> VALID_TIERS = Set.of("FRESH", "AGING", "STALE", "NEVER");

    private final JdbcTemplate jdbcTemplate;

    public FreshnessService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 分页查询任务新鲜度。
     *
     * @param taskName 任务名模糊搜索（null 不过滤）
     * @param tiers    时效分档过滤（null 或空不过滤；合法值 FRESH/AGING/STALE/NEVER）
     * @param sort     排序（worst_first 默认 / best_first）
     * @param page     页码（0-based）
     * @param size     每页大小
     */
    public PageResult<FreshnessRow> query(String taskName, List<String> tiers, String sort,
                                           int page, int size) {
        int effectivePage = Math.max(0, page);
        int effectiveSize = Math.min(Math.max(1, size), 200);

        // 核心聚合子查询：所有未删除任务 LEFT JOIN 最近成功时间
        // 使用 EXTRACT(EPOCH FROM ...) 兼容 H2-PG 与真实 PG
        String baseSql = "SELECT td.id AS task_id, td.name AS task_name, "
                + "MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END) AS last_success_at, "
                + "CASE "
                + "  WHEN MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END) IS NULL THEN 'NEVER' "
                + "  WHEN EXTRACT(EPOCH FROM (NOW() - MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END))) / 3600.0 <= ? THEN 'FRESH' "
                + "  WHEN EXTRACT(EPOCH FROM (NOW() - MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END))) / 3600.0 <= ? THEN 'AGING' "
                + "  ELSE 'STALE' "
                + "END AS freshness_tier "
                + "FROM task_def td "
                + "LEFT JOIN task_instance ti ON ti.task_id = td.id AND ti.deleted = 0 "
                + "WHERE td.deleted = 0 "
                + "GROUP BY td.id, td.name";

        List<Object> params = new ArrayList<>();
        params.add(FRESH_HOURS);
        params.add(STALE_HOURS);

        // 任务名筛选（追加到 HAVING）
        boolean hasNameFilter = taskName != null && !taskName.isBlank();
        if (hasNameFilter) {
            baseSql += " HAVING UPPER(td.name) LIKE ?";
            params.add("%" + taskName.trim().toUpperCase() + "%");
        }

        // 分档筛选（在 HAVING 上对计算列过滤）
        List<String> validTiers = null;
        if (tiers != null && !tiers.isEmpty()) {
            validTiers = tiers.stream()
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .filter(VALID_TIERS::contains)
                    .toList();
            if (!validTiers.isEmpty()) {
                // HAVING 中的 CASE 表达式需要 FRESH_HOURS / STALE_HOURS 参数
                String tierCaseSql = "CASE "
                        + "WHEN MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END) IS NULL THEN 'NEVER' "
                        + "WHEN EXTRACT(EPOCH FROM (NOW() - MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END))) / 3600.0 <= ? THEN 'FRESH' "
                        + "WHEN EXTRACT(EPOCH FROM (NOW() - MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END))) / 3600.0 <= ? THEN 'AGING' "
                        + "ELSE 'STALE' END";

                if (hasNameFilter) {
                    baseSql += " AND UPPER(" + tierCaseSql + ") IN (";
                } else {
                    baseSql += " HAVING UPPER(" + tierCaseSql + ") IN (";
                }
                for (int i = 0; i < validTiers.size(); i++) {
                    baseSql += (i > 0 ? "," : "") + "?";
                }
                baseSql += ")";

                // 参数顺序：FRESH_HOURS, STALE_HOURS（CASE 表达式需要），然后各 tier 值
                params.add(FRESH_HOURS);
                params.add(STALE_HOURS);
                for (String t : validTiers) {
                    params.add(t);
                }
            }
        }

        // Count（包一层子查询）
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (" + baseSql + ") sub", Long.class, params.toArray());
        long totalCount = total != null ? total : 0;

        // 排序
        String orderSql;
        if ("best_first".equalsIgnoreCase(sort)) {
            orderSql = " ORDER BY "
                    + "CASE freshness_tier WHEN 'FRESH' THEN 1 WHEN 'AGING' THEN 2 WHEN 'STALE' THEN 3 ELSE 4 END ASC, "
                    + "last_success_at DESC NULLS LAST";
        } else {
            // worst_first 默认：NEVER 优先，同档内最陈旧在前
            orderSql = " ORDER BY "
                    + "CASE freshness_tier WHEN 'NEVER' THEN 1 WHEN 'STALE' THEN 2 WHEN 'AGING' THEN 3 ELSE 4 END ASC, "
                    + "last_success_at ASC NULLS FIRST";
        }

        // 分页查询
        int offset = effectivePage * effectiveSize;
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(effectiveSize);
        pageParams.add(offset);

        List<FreshnessRow> items = jdbcTemplate.query(
                "SELECT * FROM (" + baseSql + ") ranked" + orderSql
                        + " LIMIT ? OFFSET ?",
                (rs, n) -> {
                    Long taskId = rs.getLong("task_id");
                    String name = rs.getString("task_name");
                    LocalDateTime lastSuccess = rs.getTimestamp("last_success_at") != null
                            ? rs.getTimestamp("last_success_at").toLocalDateTime() : null;
                    String tier = rs.getString("freshness_tier");
                    Long ageHours = null;
                    if (lastSuccess != null) {
                        ageHours = Duration.between(lastSuccess, LocalDateTime.now()).toHours();
                    }
                    return new FreshnessRow(taskId, name, tier,
                            lastSuccess != null ? lastSuccess.toString() : null,
                            ageHours);
                },
                pageParams.toArray());

        return new PageResult<>(items, totalCount, effectivePage, effectiveSize);
    }

    /** 新鲜度行 DTO。 */
    public record FreshnessRow(Long taskId, String name, String tier,
                                String lastSuccessAt, Long ageHours) {}

    /** 分页结果。 */
    public record PageResult<T>(List<T> items, long total, int page, int size) {}
}
