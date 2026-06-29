package com.dataweave.master.application.lineage;

/**
 * 列级血缘可信度。
 *
 * <ul>
 *   <li>{@link #CONFIRMED} —— 元数据齐全且 Calcite 列溯源成功，或纯解析可信。</li>
 *   <li>{@link #UNVERIFIED} —— 降级推断（缺元数据 / {@code *} 不可展开 / 溯源为空时的启发式）。</li>
 *   <li>{@link #CONFLICT} —— 与 Agent {@code .task.yaml} 列级声明冲突（标记，不静默丢弃）。</li>
 * </ul>
 */
public enum Confidence {
    CONFIRMED,
    UNVERIFIED,
    CONFLICT
}
