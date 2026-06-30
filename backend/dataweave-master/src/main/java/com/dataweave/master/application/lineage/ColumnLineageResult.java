package com.dataweave.master.application.lineage;

import java.util.List;

/**
 * {@code SqlColumnLineageExtractor.extract} 的返回。
 *
 * @param parsed   是否进入了列级解析（false = 完全降级到表级）
 * @param edges    列级派生边（可空列表，绝不为 {@code null}）
 * @param degraded 是否发生过降级（部分列 UNVERIFIED 或 {@code *} 未展开）
 */
public record ColumnLineageResult(boolean parsed, List<ColumnEdge> edges, boolean degraded) {

    public static ColumnLineageResult unparsed() {
        return new ColumnLineageResult(false, List.of(), false);
    }

    public static ColumnLineageResult parsed(List<ColumnEdge> edges, boolean degraded) {
        return new ColumnLineageResult(true, edges, degraded);
    }
}
