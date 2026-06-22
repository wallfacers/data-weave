package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.AbstractTaskExecutor;
import com.dataweave.worker.domain.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * ECHO 任务执行器（task-run-decouple）：把任务内容逐行回显到日志，立即成功。
 *
 * <p>最轻量真实执行器——用于验证「保存即测试运行 + 实时滚屏日志」闭环（不再走 all-in-one 无执行器模拟分支）。
 * 内容已在调度侧完成 {@code ${...}}/{@code $bizdate} 占位符替换，这里只负责逐行 {@code onLine} 回调。
 */
@Component
public class EchoTaskExecutor extends AbstractTaskExecutor {

    @Override
    public String type() {
        return "ECHO";
    }

    @Override
    protected ExecutionResult doExecute(ExecutionContext ctx, Consumer<String> onLine) {
        String content = ctx.content() == null ? "" : ctx.content();
        StringBuilder captured = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (onLine != null) {
                onLine.accept(line);
            }
            captured.append(line).append('\n');
        }
        return new ExecutionResult(true, 0, captured.toString(), "", false, false, "执行完成");
    }
}
