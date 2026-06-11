package com.dataweave.master.domain;

/**
 * 实例状态常量（task_instance / workflow_instance 的 state 取值），避免散落字面量拼写漂移。
 * 节点态机：NOT_RUN →(上游就绪) WAITING →(认领) DISPATCHED →(worker 回报) RUNNING → SUCCESS|FAILED|STOPPED；
 * 软抢占 RUNNING/DISPATCHED → PREEMPTED →(回炉) WAITING。
 */
public final class InstanceStates {

    public static final String NOT_RUN = "NOT_RUN";
    public static final String WAITING = "WAITING";
    public static final String DISPATCHED = "DISPATCHED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String STOPPED = "STOPPED";
    public static final String PREEMPTED = "PREEMPTED";
    public static final String PAUSED = "PAUSED";

    /** 唤醒频道：发布即请求一轮调度（跨 master 广播 / 进程内直达）。 */
    public static final String WAKE_CHANNEL = "dw:wake";

    private InstanceStates() {
    }

    public static boolean isTerminal(String state) {
        return SUCCESS.equals(state) || FAILED.equals(state) || STOPPED.equals(state);
    }
}
