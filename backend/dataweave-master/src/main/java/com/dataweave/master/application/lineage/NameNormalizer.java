package com.dataweave.master.application.lineage;

import org.apache.calcite.sql.SqlIdentifier;

import java.util.List;

/**
 * 表/列名规范化的<b>唯一</b>真相源。
 *
 * <p>表级（{@code SqlTableExtractor}）与列级（{@code SqlColumnLineageExtractor}）必须共用同一套规则，
 * 否则列会挂错或挂空到 018 建的 {@code :Table}/{@code :Column} 节点（契约 C3）。
 *
 * <p>规则：保留 schema 前缀、点分连接、去引号（解析器已去）、大小写保持原样（解析器
 * {@code caseSensitive=false} + {@code UNCHANGED} casing，比对时按需小写）。
 */
public final class NameNormalizer {

    private NameNormalizer() {
    }

    /** 点分标识符 → 规范化点分名（保留 schema 前缀）。 */
    public static String table(List<String> parts) {
        return parts == null ? null : String.join(".", parts);
    }

    /** {@link SqlIdentifier} → 规范化点分表名。 */
    public static String table(SqlIdentifier id) {
        return id == null ? null : String.join(".", id.names);
    }

    /** 列名规范化（去首尾空白；保持大小写）。 */
    public static String column(String raw) {
        return raw == null ? null : raw.trim();
    }

    /** 大小写无关比对用的折叠键。 */
    public static String fold(String name) {
        return name == null ? null : name.trim().toLowerCase();
    }
}
