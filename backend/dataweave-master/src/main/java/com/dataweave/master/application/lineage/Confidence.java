package com.dataweave.master.application.lineage;

/**
 * 列级血缘可信度。
 *
 * <ul>
 *   <li>{@link #CONFIRMED} —— 元数据齐全且 Calcite 列溯源成功，或声明与推导一致。</li>
 *   <li>{@link #UNVERIFIED} —— 降级推断（缺元数据 / {@code *} 不可展开 / 溯源为空时的启发式）。</li>
 *   <li>{@link #CONFLICT} —— 与 Agent {@code .task.yaml} 列级声明冲突（标记，不静默丢弃）。</li>
 *   <li>{@link #DECLARED} —— 仅 Agent 声明、推导未印证（024 声明兜底）。</li>
 * </ul>
 */
public enum Confidence {
    CONFIRMED,
    UNVERIFIED,
    CONFLICT,
    DECLARED
}
