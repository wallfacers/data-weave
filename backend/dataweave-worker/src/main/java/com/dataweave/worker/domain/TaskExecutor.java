package com.dataweave.worker.domain;

/**
 * 任务执行器抽象接口。MVP 仅骨架。
 *
 * <p>后期接缝：Master 通过 Redis 队列 / gRPC / MQ 下发任务，Worker 拉取后选择对应
 * {@link TaskExecutor} 实现执行（SQL / Shell / Spark / Python ...）。
 */
public interface TaskExecutor {

    /** 该执行器支持的任务类型，如 SQL / SHELL。 */
    String type();

    /**
     * 执行任务内容，返回执行日志/结果摘要。
     *
     * @param content 任务执行内容（如 SQL 文本、shell 命令）
     */
    ExecutionResult execute(String content);

    /** 执行结果：状态 + 日志。 */
    record ExecutionResult(boolean success, String log) {
    }
}
