package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PythonTaskExecutor 单元测试：真实 python3 进程执行、退出码透传、超时 kill、
 * 数据源配置路径注入、stdout/stderr 合并、CRLF 规范化（FR-007，与 ShellTaskExecutor 同构）。
 *
 * <p>前置：本机 python3 在 PATH（MVP 要求，与 ShellTaskExecutorTest 需 bash 同性质）。
 */
class PythonTaskExecutorTest {

    private final PythonTaskExecutor executor = new PythonTaskExecutor();

    private ExecutionContext pyCtx(String content, int timeoutSec, String pythonConfigPath) {
        return new ExecutionContext(content, null, 1, timeoutSec, "TEST", "PYTHON",
                null, null, pythonConfigPath);
    }

    @Test
    void typeIsPython() {
        assertThat(executor.type()).isEqualTo("PYTHON");
    }

    @Test
    void executesPrintAndReturnsSuccess() {
        ExecutionContext ctx = pyCtx("print('hello-py')", 10, null);
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(ctx, lines::add);

        assertThat(r.success()).isTrue();
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.stdout()).contains("hello-py");
        assertThat(lines).contains("hello-py");
    }

    @Test
    void capturesMultiLineOutput() {
        ExecutionContext ctx = pyCtx("print('l1')\nprint('l2')\nprint('l3')", 10, null);
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(ctx, lines::add);

        assertThat(r.success()).isTrue();
        assertThat(lines).containsExactly("l1", "l2", "l3");
    }

    @Test
    void sysExitNonZeroReportsFailure() {
        // sys.exit(42) → exitCode 42（忠实透传，SC-004）
        ExecutionContext ctx = pyCtx("import sys; sys.exit(42)", 10, null);
        TaskExecutor.ExecutionResult r = executor.execute(ctx, null);

        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(42);
        assertThat(r.message()).contains("42");
    }

    @Test
    void exceptionReportsFailure() {
        // 抛未捕获异常 → python3 退出码 1
        ExecutionContext ctx = pyCtx("raise RuntimeError('boom')", 10, null);
        TaskExecutor.ExecutionResult r = executor.execute(ctx, null);

        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isNotEqualTo(0);
        assertThat(r.stdout()).contains("RuntimeError");
    }

    @Test
    void timeoutKillsProcess() {
        ExecutionContext ctx = pyCtx("import time; time.sleep(60)", 2, null);
        long start = System.currentTimeMillis();
        TaskExecutor.ExecutionResult r = executor.execute(ctx, null);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(r.success()).isFalse();
        assertThat(r.timedOut()).isTrue();
        assertThat(elapsed).isLessThan(15000);
    }

    @Test
    void emptyContentReturnsFailure() {
        TaskExecutor.ExecutionResult r = executor.execute(pyCtx("   ", 10, null), null);
        assertThat(r.success()).isFalse();
        assertThat(r.message()).contains("为空");
    }

    @Test
    void nullContentReturnsFailure() {
        TaskExecutor.ExecutionResult r = executor.execute(pyCtx(null, 10, null), null);
        assertThat(r.success()).isFalse();
    }

    @Test
    void pythonConfigPathInjectedAsEnvVar() {
        // pythonConfigPath → DW_DATASOURCE_CONFIG 环境变量（脚本可读）
        ExecutionContext ctx = pyCtx(
                "import os; print('CFG=' + os.environ.get('DW_DATASOURCE_CONFIG', 'none'))",
                10, "/tmp/dw-test-cfg.json");
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(ctx, lines::add);

        assertThat(r.success()).isTrue();
        assertThat(r.stdout()).contains("CFG=/tmp/dw-test-cfg.json");
    }

    @Test
    void stderrMergedIntoStdout() {
        // redirectErrorStream=true → stderr 合并到 stdout（与 Shell 一致）
        ExecutionContext ctx = pyCtx("import sys; print('to-stderr', file=sys.stderr)", 10, null);
        TaskExecutor.ExecutionResult r = executor.execute(ctx, null);

        assertThat(r.success()).isTrue();
        assertThat(r.stdout()).contains("to-stderr");
    }

    @Test
    void normalizesCrlfLineEndings() {
        // Windows CRLF 脚本：规范化后 python 不报 EOL SyntaxError
        String script = "print('start')\r\nprint('done')\r\n";
        ExecutionContext ctx = pyCtx(script, 10, null);
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = executor.execute(ctx, lines::add);

        assertThat(r.success()).isTrue();
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(lines).contains("start", "done");
    }

    /**
     * D4 回归（061 US1 真跑暴露）：解释器缺失（如 Alpine worker 无 python3）时，诊断信息
     * 必须经 onLine 流入实例日志——否则操作者只见框架裸 "-1" 无从判因。此前该消息仅进
     * ExecutionResult.message 字段、不流日志。用 seam 覆盖 interpreterExecutable() 为不存在
     * 的命令触发 IOException 路径。
     */
    @Test
    void missingInterpreterSurfacesDiagnosticToLog() {
        PythonTaskExecutor missing = new PythonTaskExecutor() {
            @Override
            protected String interpreterExecutable() {
                return "python3-definitely-not-installed-xyz";
            }
        };
        ExecutionContext ctx = pyCtx("print('never runs')", 10, null);
        List<String> lines = new ArrayList<>();
        TaskExecutor.ExecutionResult r = missing.execute(ctx, lines::add);

        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(-1);
        // 关键断言：诊断行真流入 onLine（此前静默）
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("无法启动"));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("python3-definitely-not-installed-xyz"));
    }
}
