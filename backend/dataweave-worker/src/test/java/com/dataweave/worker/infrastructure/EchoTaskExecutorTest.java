package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EchoTaskExecutor：内容逐行回显、立即成功（验证「保存即测试运行 + 实时日志」最轻量闭环）。
 */
class EchoTaskExecutorTest {

    private final EchoTaskExecutor executor = new EchoTaskExecutor();

    @Test
    void type_isECHO() {
        assertThat(executor.type()).isEqualTo("ECHO");
    }

    @Test
    void echoesEachLine() {
        ExecutionContext ctx = new ExecutionContext("line1\nline2\nline3", null, 1, 10);
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult result = executor.execute(ctx, lines::add);

        assertThat(result.success()).isTrue();
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(lines).containsExactly("line1", "line2", "line3");
    }

    @Test
    void emptyContentStillSucceeds() {
        ExecutionContext ctx = new ExecutionContext("", null, 1, 10);
        TaskExecutor.ExecutionResult result = executor.execute(ctx, null);
        assertThat(result.success()).isTrue();
    }
}
