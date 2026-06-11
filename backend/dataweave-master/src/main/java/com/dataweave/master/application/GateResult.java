package com.dataweave.master.application;

/**
 * 闸门处理结果。工具/CLI/applyFix 据此回应调用方。
 *
 * @param outcome              EXECUTED / PENDING_APPROVAL / REJECTED
 * @param actionId             落库的 agent_action id（审批单即此 id）
 * @param level                裁决等级
 * @param message              面向用户的反馈（执行结果 / 待审批摘要 / 拒绝原因）
 * @param summary              审批卡片摘要（审批路径）
 * @param requiresConfirmation L3 批准需二次确认
 * @param resultInstanceId     执行路径若产生重跑实例，其 id
 */
public record GateResult(Outcome outcome,
                         Long actionId,
                         String level,
                         String message,
                         String summary,
                         boolean requiresConfirmation,
                         java.util.UUID resultInstanceId) {

    public enum Outcome {
        EXECUTED, PENDING_APPROVAL, REJECTED
    }

    public boolean executed() {
        return outcome == Outcome.EXECUTED;
    }

    public boolean pending() {
        return outcome == Outcome.PENDING_APPROVAL;
    }

    public boolean rejected() {
        return outcome == Outcome.REJECTED;
    }
}
