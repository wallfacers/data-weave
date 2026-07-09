package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.AbstractTaskExecutor;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.ExecutionContext.EngineSubmitRef;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * DataX 任务执行器（FR-004）。
 *
 * <p>以 {@code datax.py} 子进程提交 DataX job JSON，与 {@link SparkTaskExecutor}
 * 同构（ProcessBuilder → 逐行 onLine → waitFor(timeout) → destroyForcibly
 * → exitCode 忠实透传）。
 *
 * <p>环境缺失（无 DATAX_HOME / datax.py 不可用）→ {@code ExecutionResult.skipped(String)}
 * （FR-008/009），不伪装成功、不阻塞；job 文件缺失 → 真实失败。
 * 本地 {@code dw run} 与服务端经同一执行器零改动漂移（原则 III fidelity）。
 */
@Component
public class DataXTaskExecutor extends AbstractTaskExecutor {

    private static final int MAX_CAPTURED_LINES = 5000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 3600;

    @Override
    public String type() {
        return "DATAX";
    }

    @Override
    protected ExecutionResult doExecute(ExecutionContext ctx, Consumer<String> onLine) throws Exception {
        EngineSubmitRef ref = ctx.engine();

        // SKIPPED 判定
        String reason = skipReason(ref);
        if (reason != null) {
            emit(onLine, reason);
            return ExecutionResult.skipped(reason);
        }

        String content = ctx.content() == null ? "" : ctx.content().replace("\r\n", "\n").replace('\r', '\n');
        if (content.isBlank()) {
            String msg = "DataX 作业内容为空";
            emit(onLine, msg);
            return new ExecutionResult(false, -1, "", "", false, false, msg);
        }

        Path jobFile = null;
        try {
            jobFile = writeTempFile(content, ".json");
            List<String> command = buildCommand(ref.engineHome(), jobFile.toString());
            return runSubprocess(command, ctx, onLine);
        } finally {
            if (jobFile != null) {
                cleanup(jobFile);
            }
        }
    }

    // ---- public static 方法（可单测，无副作用）----

    /**
     * SKIPPED 触发判定。返回非 null = 跳过原因，null = 可执行。
     */
    static String skipReason(EngineSubmitRef ref) {
        String home = resolveEngineHome(ref);
        if (home == null || home.isBlank()) {
            return "已跳过：本地无 DataX 环境（DATAX_HOME 未配置）";
        }
        Path dataxPy = Path.of(home, "bin", "datax.py");
        if (!Files.exists(dataxPy)) {
            return "已跳过：本地无 DataX 环境（" + dataxPy + " 不存在）";
        }
        return null;
    }

    /**
     * 构造 datax.py 命令（可单测纯函数）。
     *
     * @param engineHome DATAX_HOME 路径
     * @param jobPath    job JSON 文件路径
     */
    static List<String> buildCommand(String engineHome, String jobPath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(engineHome, "bin", "datax.py").toString());
        cmd.add(jobPath);
        return cmd;
    }

    /** 解析 engineHome：优先 EngineSubmitRef，否则环境变量 DATAX_HOME。 */
    static String resolveEngineHome(EngineSubmitRef ref) {
        if (ref != null && ref.engineHome() != null && !ref.engineHome().isBlank()) {
            return ref.engineHome();
        }
        return System.getenv("DATAX_HOME");
    }

    // ---- 子进程执行（复用 Spark/Shell/Python 范式）----

    private ExecutionResult runSubprocess(List<String> command, ExecutionContext ctx, Consumer<String> onLine)
            throws Exception {
        int timeout = ctx.timeoutSeconds() > 0 ? ctx.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("DW_ATTEMPT", String.valueOf(ctx.attempt()));
        if (ctx.bizDate() != null) {
            pb.environment().put("DW_BIZ_DATE", ctx.bizDate());
        }
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new ExecutionResult(false, -1, "", "", false, false,
                    "[DATAX] 无法启动 datax.py: " + e.getMessage());
        }

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
                // 进程被强杀后流关闭
            }
        }, "datax-reader");
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

    // ---- helpers ----

    private static Path writeTempFile(String content, String suffix) throws IOException {
        Path tmp = Files.createTempFile("dw-datax-", suffix);
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        return tmp;
    }

    private static void cleanup(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void emit(Consumer<String> onLine, String line) {
        if (onLine != null) {
            onLine.accept(line);
        }
    }
}
