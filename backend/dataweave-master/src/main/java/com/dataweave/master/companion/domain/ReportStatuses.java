package com.dataweave.master.companion.domain;

/**
 * 巡检汇报处置状态：{@code UNREAD → READ → CLOSED}（单向；CLOSED 终态，不自动重现）。
 *
 * <p>项目级共享（clarify 决议）：任一成员关闭后对项目内全员消失。CLOSED 靠服务端状态为准，
 * 多客户端经 SSE {@code report:closed} 事件同步移除（边界用例）。
 */
public final class ReportStatuses {

    public static final String UNREAD = "UNREAD";
    public static final String READ = "READ";
    public static final String CLOSED = "CLOSED";

    private ReportStatuses() {}

    public static boolean isClosed(String status) {
        return CLOSED.equals(status);
    }
}
