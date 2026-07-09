package com.dataweave.worker.domain;

import java.util.UUID;

/**
 * 外部作业句柄回写接口（060 节点容错闭环 — FR-023）。
 *
 * <p>FlinkTaskExecutor 在 detached 提交流式作业成功后，通过此接口将外部作业句柄
 *（JobID + REST 端点）持久化到 task_instance.external_job_handle。
 *
 * <p><b>当前状态：桩</b>——等待 060 Foundational（schema 加列）后，master 侧提供
 * REST 端点，worker 侧提供 HTTP 实现。在此之前，句柄写入为 no-op（不影响有界作业）。
 *
 * <p>实现约束：此接口由 060-us3 独占，不与 WorkerReportService（另一个 agent）冲突。
 */
@FunctionalInterface
public interface ExternalJobHandleWriter {

    /**
     * 持久化外部作业句柄。
     *
     * @param taskInstanceId 任务实例 ID
     * @param handle         外部作业句柄（JSON，含 jobId + restEndpoint）
     */
    void write(UUID taskInstanceId, String handle);

    /** No-op 桩实现：等待 Foundational 完成后替换为 HTTP 实现。 */
    static ExternalJobHandleWriter noop() {
        return (id, handle) -> {
            // TODO(060-Foundational): 替换为 HTTP POST /api/ops/instances/{id}/external-job-handle
        };
    }
}
