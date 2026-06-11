package com.dataweave.worker.domain;

import java.util.function.Consumer;

/**
 * 任务执行器抽象接口（design D9）。
 *
 * <p>与 {@link com.dataweave.worker.application.ControlledCommandExecutor} 独立：
 * 本接口用于调度任务（信任链为发布流程审查，不设白名单）；
 * ControlledCommandExecutor 仅服务 Agent {@code node_exec} 诊断命令（白名单防护）。
 * 两条路径不得混用。
 *
 * <p>实现类通过 {@link #type()} 声明支持的任务类型（SHELL / SQL / …），调度侧按类型分发。
 */
public interface TaskExecutor {

    /** 该执行器支持的任务类型，如 SQL / SHELL。 */
    String type();

    /**
     * 执行任务内容。
     *
     * @param ctx   执行上下文（内容、环境变量、超时）
     * @param onLine 逐行输出回调（stdout/stderr 每行调用一次，可为空行），用于实时管道；
     *               若为 {@code null} 则不回调
     * @return 执行结果
     */
    ExecutionResult execute(ExecutionContext ctx, Consumer<String> onLine);

    /**
     * 执行结果。
     *
     * @param success   是否成功（exitCode==0 且未超时）
     * @param exitCode  进程退出码（超时/启动失败为 -1）
     * @param stdout    标准输出（可能截断）
     * @param stderr    标准错误（可能截断）
     * @param truncated 输出是否被截断
     * @param timedOut  是否超时终止
     * @param message   面向用户/审计的摘要
     */
    record ExecutionResult(boolean success, int exitCode, String stdout, String stderr,
                           boolean truncated, boolean timedOut, String message) {
    }
}
