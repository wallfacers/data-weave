package com.dataweave.worker.domain;

/**
 * 执行器基类：统一异常包装与日志前缀。具体执行逻辑由子类实现 {@link #doExecute}。
 */
public abstract class AbstractTaskExecutor implements TaskExecutor {

    @Override
    public ExecutionResult execute(String content) {
        try {
            return doExecute(content);
        } catch (Exception e) {
            return new ExecutionResult(false, "[" + type() + "] 执行失败: " + e.getMessage());
        }
    }

    protected abstract ExecutionResult doExecute(String content) throws Exception;
}
