package com.dataweave.master.application.lineage;

/**
 * 列级派生方式。
 *
 * <ul>
 *   <li>{@link #DIRECT} —— 1:1 直引（{@code SELECT a FROM t}）。</li>
 *   <li>{@link #EXPRESSION} —— 标量表达式（{@code a + b}、{@code fn(a)}），可能多源。</li>
 *   <li>{@link #AGGREGATE} —— 聚合算子（{@code SUM/COUNT/...}）。</li>
 * </ul>
 */
public enum Transform {
    DIRECT,
    EXPRESSION,
    AGGREGATE
}
