package com.dataweave.worker.domain;

import java.util.UUID;

/**
 * 当前执行的任务实例绑定（060 节点容错闭环 — Flink 句柄回写用）。
 *
 * <p>{@code WorkerExecService} 在调用 {@code TaskExecutor.execute} 前 {@link #bind(UUID)} 当前
 * 实例 id，执行结束 {@link #clear()}；{@code FlinkTaskExecutor} 在 detached 提交流式作业解析到
 * JobID 后，通过 {@link #currentInstanceId()} 取实例 id 并把 external_job_handle 回写 master。
 *
 * <p>ThreadLocal 语义安全：bind/execute/clear 在同一 worker 执行线程上闭合，不跨线程泄漏。
 * 非 Flink 执行器不读取此绑定，零影响。
 */
public final class CurrentExecution {

    private static final ThreadLocal<UUID> INSTANCE_ID = new ThreadLocal<>();

    private CurrentExecution() {
    }

    /** 绑定当前执行线程的任务实例 id（execute 前调用）。 */
    public static void bind(UUID taskInstanceId) {
        INSTANCE_ID.set(taskInstanceId);
    }

    /** 当前执行线程绑定的任务实例 id；未绑定返回 null。 */
    public static UUID currentInstanceId() {
        return INSTANCE_ID.get();
    }

    /** 清除绑定（execute 后 finally 调用，防线程复用泄漏）。 */
    public static void clear() {
        INSTANCE_ID.remove();
    }
}
