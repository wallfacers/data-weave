package com.dataweave.alert.infrastructure.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.util.Map;

/**
 * 跨方言取自增主键。
 *
 * <p>替代已失效的 {@code CALL IDENTITY()}：该语法是 H2 1.x 遗留函数，H2 2.x 已移除
 * （{@code Function "identity" not found}），PostgreSQL 更无此函数 —— 原写法在 H2 2.x/PG 上
 * INSERT 已自动提交、紧接的 id 回取却抛错，实体 id 永远为 null（异常被上层吞掉时表现为「行落了但拿不到 id」）。
 *
 * <p>改用 Spring {@link GeneratedKeyHolder} 请求 {@code id} 生成列，H2 2.x 与 PostgreSQL 通用。
 * 沿用各 repository 原有 INSERT SQL 与参数顺序不变，仅替换主键回取方式。alert 各实体字段均为
 * String/数值/LocalDateTime（无 enum/自定义类型），{@code setObject} 直传安全。
 */
final class JdbcInsertSupport {

    private JdbcInsertSupport() {
    }

    /** 执行 INSERT 并返回自增主键（{@code id} 列）。 */
    static long insertReturningId(JdbcTemplate jdbc, String sql, Object... args) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, kh);
        return extractId(kh, sql);
    }

    private static long extractId(KeyHolder kh, String sql) {
        // 单列生成键：getKey() 直接给值；多列（部分驱动回带其它列）时退回按 "id" 取。
        Map<String, Object> keys = kh.getKeys();
        if (keys != null && keys.size() == 1) {
            Object only = keys.values().iterator().next();
            if (only instanceof Number n) return n.longValue();
        }
        if (keys != null) {
            Object idVal = keys.get("id");
            if (idVal == null) idVal = keys.get("ID");
            if (idVal instanceof Number n) return n.longValue();
        }
        Number key = kh.getKey();
        if (key != null) return key.longValue();
        throw new IllegalStateException("insert 未返回生成主键: " + sql);
    }
}
