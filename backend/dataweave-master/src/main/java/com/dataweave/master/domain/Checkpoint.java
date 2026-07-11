package com.dataweave.master.domain;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 062 实时任务检查点（task_checkpoint 行）：一个 long_running 实例的可恢复进度快照。
 *
 * <p>滚动保留最近 N 个成功检查点（默认 N=3，可配置）；续跑（resumeFromCheckpoint）时运维从中
 * 选一个作为回滚恢复点。检查点有时效性（默认 24h，可配置）——过期或非 SUCCESS 即不可用于续跑。
 *
 * @param id             检查点 UUIDv7
 * @param taskInstanceId 所属实时任务实例
 * @param ordinal        同实例内递增序号（滚动淘汰：保留 ordinal 最大的 N 个）
 * @param checkpointPath 引擎侧 savepoint/checkpoint 路径（Flink savepointPath）
 * @param externalRef    引擎侧触发句柄/请求 ID（可选）
 * @param status         IN_PROGRESS / SUCCESS / FAILED / EXPIRED
 * @param sizeBytes      检查点大小（引擎返回则填）
 * @param completedAt    status=SUCCESS 的时刻（过期判定依据）
 * @param createdAt      创建时刻
 */
public record Checkpoint(
        UUID id,
        UUID taskInstanceId,
        int ordinal,
        String checkpointPath,
        String externalRef,
        String status,
        Long sizeBytes,
        LocalDateTime completedAt,
        LocalDateTime createdAt) {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String EXPIRED = "EXPIRED";
}
