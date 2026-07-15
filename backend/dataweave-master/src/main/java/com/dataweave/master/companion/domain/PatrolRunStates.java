package com.dataweave.master.companion.domain;

/**
 * 巡检执行历史状态机：{@code CLAIMED → RUNNING → {SUCCEEDED | FAILED | TIMEOUT}}（调度不变量② CAS 推进，禁回退）。
 *
 * <p>语义：CLAIMED=已被 UNIQUE guard 单赢创建待执行；RUNNING=SKIP LOCKED 认领后 brain 外呼中；
 * SUCCEEDED/FAILED/TIMEOUT=终态（TIMEOUT 由 reaper 标记）。
 */
public final class PatrolRunStates {

    public static final String CLAIMED = "CLAIMED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String TIMEOUT = "TIMEOUT";

    private PatrolRunStates() {}

    public static boolean isTerminal(String state) {
        return SUCCEEDED.equals(state) || FAILED.equals(state) || TIMEOUT.equals(state);
    }
}
