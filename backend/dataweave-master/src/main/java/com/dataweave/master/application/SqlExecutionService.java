package com.dataweave.master.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQL 执行工具：只读校验 + 执行。Text-to-SQL 与指标口径执行都走这里。
 * 阻塞 JDBC 与 WebFlux 混用对 MVP 可接受（调用方在 boundedElastic 调度执行）。
 */
@Service
public class SqlExecutionService {

    private final JdbcTemplate jdbcTemplate;

    // 写操作关键词黑名单，命中即拒绝
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|truncate|create|merge|grant|revoke|replace|call)\\b",
            Pattern.CASE_INSENSITIVE);

    public SqlExecutionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 只读校验：仅允许单条 SELECT 语句。
     *
     * @return null 表示通过；非 null 为拒绝原因
     */
    public String rejectReason(String sql) {
        if (sql == null || sql.isBlank()) {
            return "SQL 为空。";
        }
        String trimmed = sql.trim();
        // 去尾分号便于后续判断
        String body = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
        if (body.contains(";")) {
            return "仅允许单条语句，禁止多语句。";
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            return "仅允许只读查询（SELECT）。";
        }
        if (FORBIDDEN.matcher(body).find()) {
            return "检测到写操作关键词，已拒绝执行（只读边界）。";
        }
        return null;
    }

    /**
     * 执行只读查询。调用前应先用 {@link #rejectReason} 校验。
     */
    public QueryResult query(String sql) {
        List<Map<String, Object>> raw = jdbcTemplate.queryForList(sql);
        List<String> columns = new ArrayList<>();
        if (!raw.isEmpty()) {
            columns.addAll(raw.get(0).keySet());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            rows.add(new LinkedHashMap<>(r));
        }
        return new QueryResult(columns, rows);
    }

    /** 取单值（用于指标口径聚合）。 */
    public Object queryScalar(String sql) {
        QueryResult result = query(sql);
        if (result.rows().isEmpty()) {
            return null;
        }
        Map<String, Object> first = result.rows().get(0);
        return first.isEmpty() ? null : first.values().iterator().next();
    }
}
