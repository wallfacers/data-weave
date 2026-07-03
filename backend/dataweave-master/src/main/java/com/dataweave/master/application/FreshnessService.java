package com.dataweave.master.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * 分页查询任务新鲜度（按 tenant + project 作用域）。
     *
     * <p>036 FR-016：消除全租户裸扫，{@code task_def} 维度按 {@code (tenant_id, project_id)}
     * 收敛；任务实例继承任务归属，不再 LEFT JOIN 处另加过滤。
     *
     * @param tenantId 租户 id（必填）
     * @param projectId 项目 id（必填）
     * @param taskName 任务名模糊搜索（null 不过滤）
     * @param tiers    时效分档过滤（null 或空不过滤；合法值 FRESH/AGING/STALE/NEVER）
     * @param sort     排序（worst_first 默认 / best_first）
     * @param page     页码（0-based）
     * @param size     每页大小
     */
    public PageResult<FreshnessRow> query(Long tenantId, Long projectId, String taskName, List<String> tiers,
                                           String sort, int page, int size) {
        int effectivePage = Math.max(0, page);
        int effectiveSize = Math.min(Math.max(1, size), 200);

        // 核心聚合子查询：任务 + 工作流调度 + 最近成功时间 + 7天趋势
        String baseSql = "SELECT td.id AS task_id, td.name AS task_name, "
                + "wd.name AS workflow_name, "
                + "wd.schedule_type, wd.cron, wd.schedule_interval_ms, "
                + "MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END) AS last_success_at, "
                + "CASE "
                + "  WHEN MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END) IS NULL THEN 'NEVER' "
                + "  WHEN EXTRACT(EPOCH FROM (NOW() - MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END))) / 3600.0 <= ? THEN 'FRESH' "
                + "  WHEN EXTRACT(EPOCH FROM (NOW() - MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END))) / 3600.0 <= ? THEN 'AGING' "
                + "  ELSE 'STALE' "
                + "END AS freshness_tier, "
                + "COALESCE((SELECT JSON_ARRAYAGG("
                + "    CASE tier WHEN 'FRESH' THEN 4 WHEN 'AGING' THEN 3 WHEN 'STALE' THEN 2 ELSE 1 END "
                + "    ORDER BY snapshot_date ASC) "
                + "  FROM freshness_task_daily "
                + "  WHERE task_id = td.id AND snapshot_date >= CURRENT_DATE - 7), '[]') AS trend_json "
                + "FROM task_def td "
                + "LEFT JOIN task_instance ti ON ti.task_id = td.id AND ti.deleted = 0 "
                + "LEFT JOIN workflow_node wn ON wn.task_id = td.id "
                + "LEFT JOIN workflow_def wd ON wd.id = wn.workflow_id AND wd.deleted = 0 "
                + "WHERE td.deleted = 0 AND td.tenant_id = ? AND td.project_id = ? "
                + "GROUP BY td.id, td.name, wd.name, wd.schedule_type, wd.cron, wd.schedule_interval_ms";

        // 参数顺序：FRESH_HOURS/STALE_HOURS（base CASE），tenantId/projectId（WHERE）
        List<Object> params = new ArrayList<>();
        params.add(FRESH_HOURS);
        params.add(STALE_HOURS);
        params.add(tenantId);
        params.add(projectId);

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
                    String workflowName = rs.getString("workflow_name");
                    String scheduleType = rs.getString("schedule_type");
                    String cron = rs.getString("cron");
                    Long intervalMsObj = rs.getObject("schedule_interval_ms", Long.class);
                    long intervalMs = intervalMsObj != null ? intervalMsObj : 0;
                    LocalDateTime lastSuccess = rs.getTimestamp("last_success_at") != null
                            ? rs.getTimestamp("last_success_at").toLocalDateTime() : null;
                    String tier = rs.getString("freshness_tier");
                    Long ageHours = null;
                    if (lastSuccess != null) {
                        ageHours = Duration.between(lastSuccess, LocalDateTime.now()).toHours();
                    }
                    String scheduleHuman = toHumanSchedule(scheduleType, cron,
                            scheduleType != null && intervalMs > 0 ? intervalMs : null);
                    int[] trend7Days = parseTrendJson(rs.getString("trend_json"));
                    return new FreshnessRow(taskId, name, workflowName, scheduleType, scheduleHuman, tier,
                            lastSuccess != null ? lastSuccess.atZone(ZoneId.systemDefault()).toInstant().toString() : null,
                            ageHours, trend7Days);
                },
                pageParams.toArray());

        return new PageResult<>(items, totalCount, effectivePage, effectiveSize);
    }

    /**
     * 拍摄每日新鲜度快照：对指定 (tenant, project) 的所有非删除任务计算当前档位，
     * 写入 freshness_task_daily 和 freshness_daily_snapshot（幂等）。
     */
    public void takeSnapshot(Long tenantId, Long projectId) {
        // 每日快照为"覆盖今日"语义：先清今日该 (tenant, project) 的行再重新聚合写入。
        // 用 DELETE + INSERT 替代 PG 专有的 ON CONFLICT，H2（PostgreSQL 兼容模式）与 PG 两库通用且幂等。
        jdbcTemplate.update(
                "DELETE FROM freshness_task_daily WHERE tenant_id = ? AND project_id = ? AND snapshot_date = CURRENT_DATE",
                tenantId, projectId);

        // 每任务档位聚合（与 query 共享相同聚合逻辑）
        String sql = """
                INSERT INTO freshness_task_daily (tenant_id, project_id, task_id, snapshot_date, tier, age_hours)
                SELECT ?, ?, td.id, CURRENT_DATE,
                  CASE
                    WHEN MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END) IS NULL THEN 'NEVER'
                    WHEN EXTRACT(EPOCH FROM (NOW() - MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END))) / 3600.0 <= ? THEN 'FRESH'
                    WHEN EXTRACT(EPOCH FROM (NOW() - MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END))) / 3600.0 <= ? THEN 'AGING'
                    ELSE 'STALE'
                  END,
                  CASE
                    WHEN MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END) IS NULL THEN NULL
                    ELSE CAST(EXTRACT(EPOCH FROM (NOW() - MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END))) / 3600.0 AS BIGINT)
                  END
                FROM task_def td
                LEFT JOIN task_instance ti ON ti.task_id = td.id AND ti.deleted = 0
                WHERE td.deleted = 0 AND td.tenant_id = ? AND td.project_id = ?
                GROUP BY td.id
                """;
        jdbcTemplate.update(sql, tenantId, projectId, FRESH_HOURS, STALE_HOURS, tenantId, projectId);

        // 项目级聚合快照同理
        jdbcTemplate.update(
                "DELETE FROM freshness_daily_snapshot WHERE tenant_id = ? AND project_id = ? AND snapshot_date = CURRENT_DATE",
                tenantId, projectId);

        String aggSql = """
                INSERT INTO freshness_daily_snapshot (tenant_id, project_id, snapshot_date, total_tasks, fresh_count, aging_count, stale_count, never_count)
                SELECT ?, ?, CURRENT_DATE,
                  COUNT(*),
                  COUNT(*) FILTER (WHERE tier = 'FRESH'),
                  COUNT(*) FILTER (WHERE tier = 'AGING'),
                  COUNT(*) FILTER (WHERE tier = 'STALE'),
                  COUNT(*) FILTER (WHERE tier = 'NEVER')
                FROM freshness_task_daily
                WHERE tenant_id = ? AND project_id = ? AND snapshot_date = CURRENT_DATE
                """;
        jdbcTemplate.update(aggSql, tenantId, projectId, tenantId, projectId);
    }

    /** 清理 90 天前的快照数据。 */
    public void cleanupOldSnapshots() {
        jdbcTemplate.update(
                "DELETE FROM freshness_task_daily WHERE snapshot_date < CURRENT_DATE - 90");
        jdbcTemplate.update(
                "DELETE FROM freshness_daily_snapshot WHERE snapshot_date < CURRENT_DATE - 90");
    }

    /**
     * 查询概览区数据：当前分布 + 日环比（依赖 freshness_daily_snapshot）。
     *
     * @return FreshnessDashboard（trend 无前一天快照时为 null）
     */
    public FreshnessDashboard getDashboard(Long tenantId, Long projectId) {
        // 当前分布：实时聚合
        FreshnessSummary summary = jdbcTemplate.query(
                """
                SELECT
                  COUNT(*) AS total,
                  COUNT(*) FILTER (WHERE freshness_tier = 'FRESH') AS fresh,
                  COUNT(*) FILTER (WHERE freshness_tier = 'AGING') AS aging,
                  COUNT(*) FILTER (WHERE freshness_tier = 'STALE') AS stale,
                  COUNT(*) FILTER (WHERE freshness_tier = 'NEVER') AS never
                FROM (
                  SELECT
                    CASE
                      WHEN MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END) IS NULL THEN 'NEVER'
                      WHEN EXTRACT(EPOCH FROM (NOW() - MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END))) / 3600.0 <= ? THEN 'FRESH'
                      WHEN EXTRACT(EPOCH FROM (NOW() - MAX(CASE WHEN ti.state = 'SUCCESS' THEN ti.finished_at ELSE NULL END))) / 3600.0 <= ? THEN 'AGING'
                      ELSE 'STALE'
                    END AS freshness_tier
                  FROM task_def td
                  LEFT JOIN task_instance ti ON ti.task_id = td.id AND ti.deleted = 0
                  WHERE td.deleted = 0 AND td.tenant_id = ? AND td.project_id = ?
                  GROUP BY td.id
                ) sub
                """,
                rs -> {
                    if (!rs.next()) return new FreshnessSummary(0, 0, 0, 0, 0);
                    return new FreshnessSummary(
                            rs.getInt("total"), rs.getInt("fresh"),
                            rs.getInt("aging"), rs.getInt("stale"), rs.getInt("never"));
                },
                FRESH_HOURS, STALE_HOURS, tenantId, projectId);

        // 前一天快照 → 日环比
        FreshnessTrend trend = jdbcTemplate.query(
                """
                SELECT total_tasks, fresh_count, aging_count, stale_count
                FROM freshness_daily_snapshot
                WHERE tenant_id = ? AND project_id = ? AND snapshot_date = CURRENT_DATE - 1
                """,
                rs -> {
                    if (!rs.next()) return null;
                    int prevTotal = rs.getInt("total_tasks");
                    return new FreshnessTrend(
                            summary.total() - prevTotal,
                            summary.fresh() - rs.getInt("fresh_count"),
                            summary.aging() - rs.getInt("aging_count"),
                            summary.stale() - rs.getInt("stale_count"));
                },
                tenantId, projectId);

        return new FreshnessDashboard(summary, trend, java.time.LocalDate.now().toString());
    }

    /** 概览区摘要。 */
    public record FreshnessSummary(int total, int fresh, int aging, int stale, int never) {}
    /** 日环比变化。 */
    public record FreshnessTrend(int totalDelta, int freshDelta, int agingDelta, int staleDelta) {}
    /** 概览区完整数据。 */
    public record FreshnessDashboard(FreshnessSummary summary, FreshnessTrend trend, String snapshotDate) {}

    /**
     * 将调度配置转换为中文人读描述（6 种核心模式 + FIXED_RATE fallback）。
     */
    static String toHumanSchedule(String scheduleType, String cron, Long intervalMs) {
        if (scheduleType == null || "MANUAL".equals(scheduleType)) return "手动";
        if ("DEPENDENCY".equals(scheduleType)) return "依赖触发";

        // FIXED_RATE / FIXED_DELAY：从 intervalMs 推导
        if (cron == null && intervalMs != null) {
            long sec = intervalMs / 1000;
            if (sec < 60) return "每 " + sec + " 秒";
            if (sec < 3600) return "每 " + (sec / 60) + " 分钟";
            if (sec < 86400) return "每 " + (sec / 3600) + " 小时";
            return "每 " + (sec / 86400) + " 天";
        }

        if (cron == null || cron.isBlank()) return "定时";

        // CRON: 标准 Quartz 6/7 字段
        String[] parts = cron.trim().split("\\s+");
        if (parts.length < 6) return cron; // unrecognized — raw fallback

        String sec = parts[0], min = parts[1], hour = parts[2],
                dom = parts[3], month = parts[4], dow = parts[5];

        // 每月某日: 0 mm HH DD * ?
        if (sec.equals("0") && !dom.equals("*") && dow.equals("?")) {
            return "每月 " + dom + " 日 " + pad2(hour) + ":" + pad2(min);
        }
        // 每周某日: 0 mm HH ? * DOW
        if (sec.equals("0") && dom.equals("?") && !dow.equals("*") && !dow.equals("?")) {
            return "每周 " + dowName(dow) + " " + pad2(hour) + ":" + pad2(min);
        }
        // 每天: 0 mm HH * * ?
        if (sec.equals("0") && dom.equals("*") && (dow.equals("?") || dow.equals("*"))) {
            return "每天 " + pad2(hour) + ":" + pad2(min);
        }
        // 每小时: 0 mm * * * ?
        if (sec.equals("0") && hour.equals("*") && dom.equals("*") && dow.equals("?")) {
            return "每小时 " + pad2(min) + " 分";
        }
        // 每 N 分钟: 0 */N * * * ?
        if (sec.equals("0") && min.startsWith("*/") && hour.equals("*") && dom.equals("*") && dow.equals("?")) {
            return "每 " + min.substring(2) + " 分钟";
        }
        // 每分钟: * * * * * ? 或 0/1 * * * * ?
        if ((sec.equals("*") || sec.equals("0/1")) && min.equals("*") && hour.equals("*")
                && dom.equals("*") && dow.equals("?")) {
            return "每分钟";
        }
        return cron; // unrecognized — raw fallback
    }

    private static String pad2(String s) { return s.length() == 1 ? "0" + s : s; }

    private static String dowName(String dow) {
        return switch (dow) {
            case "1" -> "日"; case "2" -> "一"; case "3" -> "二";
            case "4" -> "三"; case "5" -> "四"; case "6" -> "五"; case "7" -> "六";
            default -> dow;
        };
    }

    /** 将 PostgreSQL JSON_ARRAYAGG 结果解析为 int[]。兼容 H2 的 JSON array 格式。 */
    static int[] parseTrendJson(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) return new int[0];
        // JSON_ARRAYAGG → "[4, 4, 3, 3, 2]"
        String cleaned = json.replaceAll("[\\[\\]\\s]", "");
        if (cleaned.isEmpty()) return new int[0];
        String[] parts = cleaned.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException e) { result[i] = 0; }
        }
        return result;
    }

    /** 扩展新鲜度行 DTO。 */
    public record FreshnessRow(Long taskId, String name, String workflowName,
                                String scheduleType, String scheduleHuman,
                                String tier, String lastSuccessAt, Long ageHours,
                                int[] trend7Days) {}

    /** 分页结果。 */
    public record PageResult<T>(List<T> items, long total, int page, int size) {}
}
