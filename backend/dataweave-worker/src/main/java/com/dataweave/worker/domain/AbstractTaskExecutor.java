package com.dataweave.worker.domain;

import java.util.function.Consumer;

/**
 * 执行器基类：统一异常包装与日志前缀。具体执行逻辑由子类实现 {@link #doExecute}。
 */
public abstract class AbstractTaskExecutor implements TaskExecutor {

    @Override
    public ExecutionResult execute(ExecutionContext ctx, Consumer<String> onLine) {
        try {
            return doExecute(ctx, onLine);
        } catch (Exception e) {
            return new ExecutionResult(false, -1, "", "", false, false,
                    "[" + type() + "] 执行失败: " + e.getMessage());
        }
    }

    /**
     * 子类实现真实执行逻辑。
     *
     * @param ctx   执行上下文
     * @param onLine 逐行输出回调，可能为 null
     */
    protected abstract ExecutionResult doExecute(ExecutionContext ctx, Consumer<String> onLine) throws Exception;
}
