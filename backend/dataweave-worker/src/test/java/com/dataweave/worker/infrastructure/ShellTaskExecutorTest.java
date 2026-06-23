package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShellTaskExecutor 单元测试：真实进程执行、环境变量注入、超时 kill、退出码。
 */
class ShellTaskExecutorTest {

    private final ShellTaskExecutor executor = new ShellTaskExecutor();

    @Test
    void type_isSHELL() {
        assertThat(executor.type()).isEqualTo("SHELL");
    }

    @Test
    void executesEchoAndReturnsSuccess() {
        ExecutionContext ctx = new ExecutionContext("echo hello", null, 1, 10);
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult result = executor.execute(ctx, lines::add);

        assertThat(result.success()).isTrue();
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).contains("hello");
        assertThat(result.timedOut()).isFalse();
        assertThat(lines).contains("hello");
    }

    @Test
    void capturesMultiLineOutput() {
        ExecutionContext ctx = new ExecutionContext("echo line1 && echo line2 && echo line3", null, 1, 10);
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult result = executor.execute(ctx, lines::add);

        assertThat(result.success()).isTrue();
        assertThat(lines).containsExactly("line1", "line2", "line3");
    }

    @Test
    void injectsEnvironmentVariables() {
        String script = "echo DW_BIZ_DATE=$DW_BIZ_DATE DW_ATTEMPT=$DW_ATTEMPT";
        ExecutionContext ctx = new ExecutionContext(script, "2026-06-12", 3, 10);
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult result = executor.execute(ctx, lines::add);

        assertThat(result.success()).isTrue();
        assertThat(result.stdout()).contains("DW_BIZ_DATE=2026-06-12");
        assertThat(result.stdout()).contains("DW_ATTEMPT=3");
    }

    @Test
    void nonZeroExitCodeReportsFailure() {
        ExecutionContext ctx = new ExecutionContext("exit 42", null, 1, 10);
        TaskExecutor.ExecutionResult result = executor.execute(ctx, null);

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(42);
        assertThat(result.message()).contains("42");
    }

    @Test
    void timeoutKillsProcess() {
        // sleep 60 秒但设置 2 秒超时
        ExecutionContext ctx = new ExecutionContext("sleep 60", null, 1, 2);
        long start = System.currentTimeMillis();
        TaskExecutor.ExecutionResult result = executor.execute(ctx, null);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.success()).isFalse();
        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isNotEqualTo(0); // SIGKILL=137 或其他非零
        // 应在 ~2s 内返回（允许一定误差），绝不超过 60s
        assertThat(elapsed).isLessThan(15000);
    }

    @Test
    void emptyContentReturnsFailure() {
        ExecutionContext ctx = new ExecutionContext("", null, 1, 10);
        TaskExecutor.ExecutionResult result = executor.execute(ctx, null);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("为空");
    }

    @Test
    void nullContentReturnsFailure() {
        ExecutionContext ctx = new ExecutionContext(null, null, 1, 10);
        TaskExecutor.ExecutionResult result = executor.execute(ctx, null);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("为空");
    }

    @Test
    void normalizesCrlfLineEndings() {
        // 脚本来自 Windows 编辑器，行尾为 \r\n。若不规范化，bash -c 下
        // 每行末尾残留 \r，导致 `sleep 2\r` 报 "invalid time interval '2\r'"。
        String script = "echo start\r\nsleep 0.2\r\necho done\r\n";
        ExecutionContext ctx = new ExecutionContext(script, null, 1, 10);
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult result = executor.execute(ctx, lines::add);

        assertThat(result.success()).isTrue();
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).doesNotContain("invalid time interval");
        // 输出行不应残留回车符
        assertThat(lines).contains("start", "done");
    }

    @Test
    void stderrMergedIntoStdout() {
        // redirectErrorStream=true → stderr 合并到 stdout
        ExecutionContext ctx = new ExecutionContext("echo stdout && echo stderr >&2", null, 1, 10);
        TaskExecutor.ExecutionResult result = executor.execute(ctx, null);

        assertThat(result.success()).isTrue();
        assertThat(result.stdout()).contains("stdout");
        assertThat(result.stdout()).contains("stderr");
    }
}
