package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.AbstractTaskExecutor;
import com.dataweave.worker.domain.ExecutionContext;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Python 任务真实执行器（子特性 D / FR-007）。
 *
 * <p>与 {@link ShellTaskExecutor} 同构：以 {@code python3 -c <content>} 启动子进程，
 * stdout/stderr 合并按行采集、每行回调 {@code onLine}，超时 {@code destroyForcibly}，
 * 退出码忠实透传。服务器与本地 {@code LocalRunMain} **共享同一实现** → 代码级语义一致
 * （宪法原则 III phased 条款）。
 *
 * <p>数据源注入：{@link ExecutionContext#pythonConfigPath()} 指向本地数据源 JSON 配置文件，
 * 注入为环境变量 {@code DW_DATASOURCE_CONFIG}，供 Python 脚本按需读取（与 Shell 一致）。
 *
 * <p>无 {@code python3} 解释器时 {@code ProcessBuilder.start()} 抛 {@link IOException} →
 * 返回失败 + 可定位错误（FR-007），不抛错中断。
 *
 * <p>实现刻意与 {@code ShellTaskExecutor} 重复（约束：不改既有执行器），换取 SHELL/PYTHON
 * 两条路径独立、零回归。
 */
@Component
public class PythonTaskExecutor extends AbstractTaskExecutor {

    private static final int MAX_CAPTURED_LINES = 5000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 3600;

    @Override
    public String type() {
        return "PYTHON";
    }

    /** 解释器可执行名——抽为 seam 便于测试缺失解释器（IOException）路径。 */
    protected String interpreterExecutable() {
        return "python3";
    }

    @Override
    protected ExecutionResult doExecute(ExecutionContext ctx, Consumer<String> onLine) throws Exception {
        if (ctx.content() == null || ctx.content().isBlank()) {
            return new ExecutionResult(false, -1, "", "", false, false, "执行内容为空");
        }

        int timeout = ctx.timeoutSeconds() > 0 ? ctx.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;

        // 规范化行尾（同 ShellTaskExecutor）：Windows CRLF 残留 \r 会让 Python 源码产生
        // SyntaxError 或隐藏字符。统一转 \n。
        String script = ctx.content().replace("\r\n", "\n").replace('\r', '\n');

        ProcessBuilder pb = new ProcessBuilder(interpreterExecutable(), "-c", script);
        pb.environment().put("DW_ATTEMPT", String.valueOf(ctx.attempt()));
        if (ctx.bizDate() != null) {
            pb.environment().put("DW_BIZ_DATE", ctx.bizDate());
        }
        // Python 数据源配置文件路径（与 ShellTaskExecutor 同语义）
        if (ctx.pythonConfigPath() != null) {
            pb.environment().put("DW_DATASOURCE_CONFIG", ctx.pythonConfigPath());
        }
        pb.redirectErrorStream(true); // stdout + stderr 合并采集（与 Shell 一致）

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // 解释器缺失（如 worker 容器未装 python3）→ 诊断信息必须经 onLine 流入实例日志，
            // 否则操作者只见框架的裸 "-1"、无从判因（061 US1 真跑暴露：Alpine worker 无 python3
            // 时静默 -1）。message 字段仅供上层记录，不等于日志可见。
            String diag = "[PYTHON] 无法启动 " + interpreterExecutable() + " 进程: " + e.getMessage()
                    + "（请确认本机已安装 python3 且在 PATH，FR-007）";
            if (onLine != null) {
                onLine.accept(diag);
            }
            return new ExecutionResult(false, -1, "", "", false, false, diag);
        }

        // 读输出线程：独立运行，避免 readLine() 阻塞超时判定。
        List<String> captured = new ArrayList<>();
        boolean[] truncatedHolder = {false};
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (captured.size() < MAX_CAPTURED_LINES) {
                        captured.add(line);
                    } else {
                        truncatedHolder[0] = true;
                    }
                    if (onLine != null) {
                        onLine.accept(line);
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀后流关闭，正常退出
            }
        }, "python-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        boolean timedOut = false;
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            timedOut = true;
        }
        readerThread.join(5000);

        int exitCode;
        try {
            exitCode = process.exitValue();
        } catch (IllegalThreadStateException e) {
            exitCode = -1;
        }

        boolean truncated = truncatedHolder[0];
        String output = String.join("\n", captured);
        String message = timedOut
                ? "执行超时（" + timeout + "s），已终止"
                : (exitCode == 0 ? "执行完成" : "退出码 " + exitCode);

        return new ExecutionResult(exitCode == 0 && !timedOut, exitCode, output, "",
                truncated, timedOut, message);
    }
}
