package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.AbstractTaskExecutor;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
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
 * Shell 任务真实执行器（design D9 / task 3.1）。
 *
 * <p>不设命令白名单（信任链为任务内容已经发布流程审查），与 Agent 诊断用的
 * {@link com.dataweave.worker.application.ControlledCommandExecutor} 白名单路径隔离。
 *
 * <p>执行流程：
 * <ol>
 *   <li>以 {@code bash -c <content>} 启动子进程</li>
 *   <li>注入 {@code DW_BIZ_DATE} / {@code DW_ATTEMPT} 环境变量</li>
 *   <li>stdout/stderr 合并按行采集，每行回调 {@code onLine}</li>
 *   <li>超时 {@code destroyForcibly}，默认 3600s（1h）</li>
 * </ol>
 */
@Component
public class ShellTaskExecutor extends AbstractTaskExecutor {

    private static final int MAX_CAPTURED_LINES = 5000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 3600;

    @Override
    public String type() {
        return "SHELL";
    }

    /** 解释器可执行名——抽为 seam 便于测试缺失解释器（IOException）路径。 */
    protected String interpreterExecutable() {
        return "bash";
    }

    @Override
    protected ExecutionResult doExecute(ExecutionContext ctx, Consumer<String> onLine) throws Exception {
        if (ctx.content() == null || ctx.content().isBlank()) {
            return new ExecutionResult(false, -1, "", "", false, false, "执行内容为空");
        }

        int timeout = ctx.timeoutSeconds() > 0 ? ctx.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;

        // 规范化行尾：脚本可能来自 Windows 编辑器（CRLF）。若残留 \r，bash 会把它
        // 当作每行命令的一部分，例如 `sleep 2\r` 报 "invalid time interval '2\r'"。
        String script = ctx.content().replace("\r\n", "\n").replace('\r', '\n');

        ProcessBuilder pb = new ProcessBuilder(interpreterExecutable(), "-c", script);
        pb.environment().put("DW_ATTEMPT", String.valueOf(ctx.attempt()));
        if (ctx.bizDate() != null) {
            pb.environment().put("DW_BIZ_DATE", ctx.bizDate());
        }
        // 注入数据源环境变量（DW_DS_* 系列）
        if (ctx.shellEnvVars() != null) {
            pb.environment().putAll(ctx.shellEnvVars());
        }
        // 注入 Python 数据源配置路径（如果存在）
        if (ctx.pythonConfigPath() != null) {
            pb.environment().put("DW_DATASOURCE_CONFIG", ctx.pythonConfigPath());
        }
        pb.redirectErrorStream(true); // stdout + stderr 合并采集

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // 解释器缺失 → 诊断信息经 onLine 流入实例日志（同 PythonTaskExecutor，避免裸 "-1" 静默）。
            String diag = "[SHELL] 无法启动 " + interpreterExecutable() + " 进程: " + e.getMessage();
            if (onLine != null) {
                onLine.accept(diag);
            }
            return new ExecutionResult(false, -1, "", "", false, false, diag);
        }

        // 读输出线程：独立运行，避免 readLine() 阻塞超时判定
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
        }, "shell-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // 等待进程结束（带超时）
        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        boolean timedOut = false;
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            timedOut = true;
        }

        // 等待读线程收尾
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
