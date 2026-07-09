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
    /** 节点被冻结跳过（ops-center-publish-boundary）：终态、非失败，不下发不占槽；冻结节点及其传递下游（含弱依赖边）。 */
    public static final String SKIPPED = "SKIPPED";
    /**
     * 单实例保护挂起（060 节点容错闭环）：连续基础设施重派超 {@code scheduler.infra-redispatch-max} 后置此态
     * （毒任务/崩溃循环的机群保护边界）。性质：非终态、不可被 claim、不自动判 FAILED；仅人工 rerun/kill 转出。
     * 不计入 {@link #isTerminal}（非终态）。
     */
    public static final String SUSPENDED = "SUSPENDED";

    /** 失效原因：连续 infra 重派超限挂起（仅作可见性，对应 {@link #SUSPENDED} 态）。 */
    public static final String INFRA_SUSPENDED = "INFRA_SUSPENDED";

    /** 唤醒频道：发布即请求一轮调度（跨 master 广播 / 进程内直达）。 */
    public static final String WAKE_CHANNEL = "dw:wake";

    private InstanceStates() {
    }

    public static boolean isTerminal(String state) {
        return SUCCESS.equals(state) || FAILED.equals(state)
                || STOPPED.equals(state) || SKIPPED.equals(state);
    }
}
