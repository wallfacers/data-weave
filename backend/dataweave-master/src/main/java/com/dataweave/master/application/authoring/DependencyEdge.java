package com.dataweave.master.application.authoring;

/**
 * 058 任务依赖边（US1 / FR-006）：一条 from→to 的任务依赖，带来源归属。
 *
 * <p><b>origin 语义</b>（哪条通道给出该依赖，为 agent 揭示「声明 DAG」与「实际血缘」的一致/背离）：
 * <ul>
 *   <li>{@code DECLARED} —— 仅工作流 DAG（{@code workflow_edge}）声明</li>
 *   <li>{@code DERIVED}  —— 仅血缘推导（读写表经 {@code FLOWS_TO} 可达）</li>
 *   <li>{@code BOTH}     —— 两通道一致</li>
 * </ul>
 * 背离（DECLARED-only=僵依赖 / DERIVED-only=缺声明）留给 US3 诊断裁决，本边只如实标注来源。
 *
 * @param fromTaskRef 上游任务标识
 * @param toTaskRef   下游任务标识
 * @param hop         跳距（声明边=1；推导边=血缘 BFS 层级）
 * @param origin      来源归属 DECLARED | DERIVED | BOTH
 */
public record DependencyEdge(String fromTaskRef, String toTaskRef, int hop, String origin) {

    public static final String DECLARED = "DECLARED";
    public static final String DERIVED = "DERIVED";
    public static final String BOTH = "BOTH";
}
