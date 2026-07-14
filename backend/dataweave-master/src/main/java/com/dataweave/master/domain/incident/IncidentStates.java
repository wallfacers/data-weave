package com.dataweave.master.domain.incident;

/**
 * 事故状态常量（incident.state 取值），避免散落字面量拼写漂移（对齐 {@link com.dataweave.master.domain.InstanceStates} 惯例）。
 * 状态机：OPEN →(采证/诊断) ANALYZING →(分型) ACTING →(验证成功) RESOLVED
 *   ANALYZING →(模型不可用) DIAG_UNAVAILABLE →(配置恢复) ANALYZING
 *   ACTING →(CODE 分型) AWAITING_APPROVAL →(批准) ACTING；→(驳回) NEEDS_HUMAN
 *   ACTING →(超限/不可自愈) NEEDS_HUMAN →(人工标记+复验成功) RESOLVED
 *   任意非终态 →(人工直接收口) RESOLVED
 */
public final class IncidentStates {

    public static final String OPEN = "OPEN";
    public static final String ANALYZING = "ANALYZING";
    public static final String ACTING = "ACTING";
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String NEEDS_HUMAN = "NEEDS_HUMAN";
    public static final String RESOLVED = "RESOLVED";
    public static final String DIAG_UNAVAILABLE = "DIAG_UNAVAILABLE";

    private IncidentStates() {
    }

    public static boolean isTerminal(String state) {
        return RESOLVED.equals(state);
    }
}
