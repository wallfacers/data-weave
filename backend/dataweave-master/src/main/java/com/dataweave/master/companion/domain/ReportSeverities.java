package com.dataweave.master.companion.domain;

/**
 * 巡检汇报严重度。{@code INFO} 含"未完成"汇报（巡检失败兜底，FR-008）。
 * {@code DANGER}/{@code WARN} 计入"未处理异常"（驱动管家 alert 形态，data-model 派生状态节）。
 */
public final class ReportSeverities {

    public static final String DANGER = "DANGER";
    public static final String WARN = "WARN";
    public static final String OK = "OK";
    public static final String INFO = "INFO";

    private ReportSeverities() {}

    /** 是否计入"未处理异常"（驱动管家 alert 形态）。 */
    public static boolean isAnomaly(String severity) {
        return DANGER.equals(severity) || WARN.equals(severity);
    }
}
