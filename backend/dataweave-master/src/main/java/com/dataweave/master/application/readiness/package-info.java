/**
 * 051 就绪态物化子包。
 *
 * <p>把认领的就绪判定从"每轮 O(WAITING) 扫描 + Java 上游/跨周期门 + 窗口游标兜底"改为查一个物化就绪态
 * {@code task_instance.unmet_deps}（未满足依赖数），认领候选 {@code WHERE unmet_deps=0} 走索引 seek，与堆积规模脱钩。
 *
 * <h3>组件</h3>
 * <ul>
 *   <li>{@link com.dataweave.master.application.readiness.ReadinessInitializer} — 物化时算 unmet_deps 初值（权威重算）</li>
 *   <li>{@link com.dataweave.master.application.readiness.ReadinessSignalWriter} — 完成/reset 事务内 append 信号</li>
 *   <li>{@link com.dataweave.master.application.readiness.ReadinessRecompute} — scoped 重算核心（幂等，供 Initializer/Maintainer/Reconciler 复用）</li>
 *   <li>{@link com.dataweave.master.application.readiness.ReadinessMaintainer} — sweeper：信号→重算→wake</li>
 *   <li>{@link com.dataweave.master.application.readiness.ReadinessReconciler} — 低频有界对账自愈</li>
 * </ul>
 */
package com.dataweave.master.application.readiness;
