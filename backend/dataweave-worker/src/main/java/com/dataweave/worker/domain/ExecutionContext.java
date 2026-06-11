package com.dataweave.worker.domain;

/**
 * 任务执行上下文：TaskExecutor 所需的运行参数。
 *
 * <p>由调用方从 {@code DispatchCommand} 构造，传入执行器。worker 据此启动进程、
 * 注入环境变量（{@code DW_BIZ_DATE} / {@code DW_ATTEMPT}）。
 *
 * @param content        执行内容（shell 命令/脚本）
 * @param bizDate        业务日期（注入环境变量 {@code DW_BIZ_DATE}），可为 null
 * @param attempt        本次尝试序号（注入环境变量 {@code DW_ATTEMPT}）
 * @param timeoutSeconds 超时秒数（≤ 0 表示不限时）
 */
public record ExecutionContext(String content, String bizDate, int attempt, int timeoutSeconds) {
}
