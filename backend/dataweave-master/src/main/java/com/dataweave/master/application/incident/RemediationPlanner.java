package com.dataweave.master.application.incident;

import com.dataweave.master.domain.incident.IncidentClassifications;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 069 确定性梯度处置映射（US2/US3，research R1：LLM 只判分型，处置路径全由 Java 确定性编排）。
 * 输入分型+当前状态，输出唯一处置动作，无副作用、无外呼、无状态——纯函数，天然单测友好。
 */
@Component
public class RemediationPlanner {

    /** 未声明资源时的基线假设（引擎默认附近的保守起点，MB/核）。 */
    private static final int DEFAULT_MEMORY_MB = 2048;
    private static final int DEFAULT_CPU_CORES = 2;
    /** 单次资源调幅比例（受 {@code resource-step-factor-max} 护栏封顶）。 */
    private static final double BUMP_RATIO = 2.0;

    public enum ActionKind { RERUN, RESUME_CHECKPOINT, ADJUST_RESOURCES, PROPOSE_FIX, ESCALATE }

    public record Decision(ActionKind kind, String reason, Integer newMemoryMb, Integer newCpuCores) {
        static Decision of(ActionKind kind, String reason) {
            return new Decision(kind, reason, null, null);
        }
    }

    private final long memoryCapMb;
    private final double stepFactorMax;
    private final int maxAutoActions;

    public RemediationPlanner(@Value("${ops.incident.memory-cap-mb:16384}") long memoryCapMb,
                               @Value("${ops.incident.resource-step-factor-max:2.0}") double stepFactorMax,
                               @Value("${ops.incident.max-auto-actions:3}") int maxAutoActions) {
        this.memoryCapMb = memoryCapMb;
        this.stepFactorMax = stepFactorMax;
        this.maxAutoActions = maxAutoActions;
    }

    /**
     * 决策唯一处置动作。
     *
     * @param classification         诊断分型（{@link IncidentClassifications}）
     * @param autoActionCount        本事故已执行的自动处置次数（防循环红线，达上限强制升级）
     * @param streaming               是否实时任务
     * @param hasAvailableCheckpoint 是否存在可用检查点（仅实时任务有意义）
     * @param currentMemoryMb        当前声明式内存（MB）；null=未声明，走引擎默认基线
     * @param currentCpuCores        当前声明式 CPU 核数；null=未声明，走引擎默认基线
     */
    public Decision plan(String classification, int autoActionCount, boolean streaming,
                          boolean hasAvailableCheckpoint, Integer currentMemoryMb, Integer currentCpuCores) {
        if (autoActionCount >= maxAutoActions) {
            return Decision.of(ActionKind.ESCALATE, "已达自动处置上限 " + maxAutoActions + " 次，转人工介入");
        }
        // 实时任务优先从检查点续跑（不判分型——续跑本身即最优雅的自愈路径，覆盖 TRANSIENT/RESOURCE 均适用）
        if (streaming && hasAvailableCheckpoint) {
            return Decision.of(ActionKind.RESUME_CHECKPOINT, "实时任务存在可用检查点，优先从检查点续跑");
        }
        if (classification == null) {
            return Decision.of(ActionKind.ESCALATE, "诊断分型缺失，转人工介入");
        }
        return switch (classification) {
            case IncidentClassifications.TRANSIENT ->
                    Decision.of(ActionKind.RERUN, "瞬态故障（网络抖动/临时资源竞争等），直接重跑");
            case IncidentClassifications.RESOURCE -> planResourceBump(currentMemoryMb, currentCpuCores);
            case IncidentClassifications.CODE ->
                    Decision.of(ActionKind.PROPOSE_FIX, "代码缺陷，生成修复提案待人工审批发布");
            case IncidentClassifications.UPSTREAM_DATA ->
                    Decision.of(ActionKind.ESCALATE, "上游数据源存在脏数据，AI 不修改上游数据，转人工介入");
            case IncidentClassifications.CONFIG_CREDENTIAL ->
                    Decision.of(ActionKind.ESCALATE, "数据源凭据配置错误，AI 无法自愈，转人工介入");
            case IncidentClassifications.UNKNOWN -> autoActionCount == 0
                    ? Decision.of(ActionKind.RERUN, "分型未知，先做一次试探性重跑（至多一次）")
                    : Decision.of(ActionKind.ESCALATE, "分型未知且试探性重跑未解决，转人工介入");
            default -> Decision.of(ActionKind.ESCALATE, "未识别分型 [" + classification + "]，转人工介入");
        };
    }

    /** RESOURCE 分型的护栏校验：调幅按 {@link #BUMP_RATIO} 封顶 {@code stepFactorMax}，越过 {@code memoryCapMb} 转人工。 */
    private Decision planResourceBump(Integer currentMemoryMb, Integer currentCpuCores) {
        int baseMemory = currentMemoryMb != null ? currentMemoryMb : DEFAULT_MEMORY_MB;
        int baseCpu = currentCpuCores != null ? currentCpuCores : DEFAULT_CPU_CORES;
        double ratio = Math.min(BUMP_RATIO, stepFactorMax);
        long newMemory = Math.round(baseMemory * ratio);
        if (ratio <= 1.0 || newMemory > memoryCapMb || newMemory <= baseMemory) {
            return Decision.of(ActionKind.ESCALATE,
                    "资源调幅超护栏（上限 " + memoryCapMb + "MB / 调幅上限 " + stepFactorMax + "x），转人工评估");
        }
        int newCpu = Math.max(baseCpu, (int) Math.round(baseCpu * ratio));
        return new Decision(ActionKind.ADJUST_RESOURCES,
                "疑似内存/CPU 不足，按护栏内调幅（" + ratio + "x）提升资源后重跑", (int) newMemory, newCpu);
    }
}
