package com.dataweave.master.quality.domain;

/**
 * 断言失败动作（FR-001/FR-005）。
 *
 * <ul>
 *   <li>{@link #BLOCK} —— 失败则阻断其绑定任务的下游 DAG 节点（复用既有 {@code SKIPPED}，
 *       经 {@code InstanceStateMachine.casTaskState(WAITING→SKIPPED)} + 既有就绪门拦下游，零新增状态机状态，红线）。</li>
 *   <li>{@link #WARN} —— 仅记录 result + 发 {@code QUALITY_FAILED} 信号，不触碰下游状态机。</li>
 * </ul>
 * 两者 FAIL 均发 {@code QUALITY_FAILED}（喂 021）。
 */
public enum RuleAction {
    BLOCK, WARN
}
