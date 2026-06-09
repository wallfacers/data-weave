package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.AbstractTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Shell 任务执行器骨架。MVP 仅骨架，不真实执行系统命令。
 *
 * <p>TODO（后期接入）：在受控沙箱 / 隔离进程中执行 shell，采集 stdout/stderr、超时与退出码，
 * 回写 task_instances.log。需考虑安全（命令白名单 / 容器隔离）。
 */
@Component
public class ShellTaskExecutor extends AbstractTaskExecutor {

    @Override
    public String type() {
        return "SHELL";
    }

    @Override
    protected ExecutionResult doExecute(String content) {
        // TODO: 后期接入真实 shell 执行。当前仅 mock 回显。
        return new ExecutionResult(true, "[SHELL][mock] would run: " + content);
    }
}
