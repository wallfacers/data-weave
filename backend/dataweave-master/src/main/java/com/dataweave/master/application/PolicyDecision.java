package com.dataweave.master.application;

import java.util.List;

/**
 * PolicyEngine 裁决结果（纯分类，不含执行/落库）。
 *
 * @param level                最终等级
 * @param outcome              执行路径：直接执行 / 审批 / 拒绝
 * @param requiresConfirmation L3 批准需二次确认（回输对象名）
 * @param injectionDetected    命令串含注入（重定向/分隔/子命令）
 * @param reasons              抬升/拒绝原因（可读，留痕用）
 */
public record PolicyDecision(PolicyLevel level,
                             Outcome outcome,
                             boolean requiresConfirmation,
                             boolean injectionDetected,
                             List<String> reasons) {

    public enum Outcome {
        EXECUTE,           // L0/L1
        PENDING_APPROVAL,  // L2/L3
        REJECTED           // L4
    }

    public static Outcome outcomeFor(PolicyLevel level) {
        return switch (level) {
            case L0, L1 -> Outcome.EXECUTE;
            case L2, L3 -> Outcome.PENDING_APPROVAL;
            case L4 -> Outcome.REJECTED;
        };
    }
}
