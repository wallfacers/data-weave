package com.dataweave.master.quality.domain;

/**
 * 质量执行入口（FR-002）。三入口共享同一 {@code QualityCheckRunner} 与 run/result 模型，仅触发源不同：
 * <ul>
 *   <li>{@link #POST_TASK} —— 任务终态 SUCCESS 后由 {@code TaskSucceededEvent} 触发（research D2.1）。</li>
 *   <li>{@link #SCHEDULED} —— 独立调度，复用调度内核 + {@code quality_fire} guard 防重（D2.2）。</li>
 *   <li>{@link #ON_DEMAND} —— REST「立即检查」/ agent 触发（D2.3）。</li>
 * </ul>
 */
public enum CheckTrigger {
    POST_TASK, SCHEDULED, ON_DEMAND
}
