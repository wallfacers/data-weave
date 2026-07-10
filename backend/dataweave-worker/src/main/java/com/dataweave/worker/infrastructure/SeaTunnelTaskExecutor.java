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
 * SeaTunnel 任务执行器（FR-005）。
 *
 * <p>以 {@code seatunnel.sh --config <file>} 子进程提交 SeaTunnel 配置，
 * 与 {@link SparkTaskExecutor} 同构（ProcessBuilder → 逐行 onLine →
 * waitFor(timeout) → destroyForcibly → exitCode 忠实透传）。
 *
 * <p>环境缺失（无 SEATUNNEL_HOME / seatunnel.sh 不可用）→
 * {@code ExecutionResult.skipped(String)}（FR-008/009），不伪装成功、不阻塞；
 * 配置文件缺失 → 真实失败。引擎选择（Zeta/Flink/Spark）由配置决定，MVP 默认 Zeta。
 * 本地 {@code dw run} 与服务端经同一执行器零改动漂移（原则 III fidelity）。
 */
@Component
public class SeaTunnelTaskExecutor extends AbstractTaskExecutor {

    private static final int MAX_CAPTURED_LINES = 5000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 3600;

    @Override
    public String type() {
        return "SEATUNNEL";
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
            String msg = "SeaTunnel 配置内容为空";
            emit(onLine, msg);
            return new ExecutionResult(false, -1, "", "", false, false, msg);
        }

        Path configFile = null;
        try {
            configFile = writeTempFile(content, ".conf");
            List<String> command = buildCommand(ref.engineHome(), configFile.toString());
            return runSubprocess(command, ctx, onLine);
        } finally {
            if (configFile != null) {
                cleanup(configFile);
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
            return "已跳过：本地无 SeaTunnel 环境（SEATUNNEL_HOME 未配置）";
        }
        Path seatunnelSh = Path.of(home, "bin", "seatunnel.sh");
        if (!Files.exists(seatunnelSh)) {
            return "已跳过：本地无 SeaTunnel 环境（" + seatunnelSh + " 不存在）";
        }
        return null;
    }

    /**
     * 构造 seatunnel.sh 命令（可单测纯函数）。
     * 默认 Zeta local 模式（-m local），无需外部集群。
     *
     * @param engineHome SEATUNNEL_HOME 路径
     * @param configPath 配置文件路径
     */
    static List<String> buildCommand(String engineHome, String configPath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(engineHome, "bin", "seatunnel.sh").toString());
        cmd.add("--master");
        cmd.add("local");
        cmd.add("--config");
        cmd.add(configPath);
        return cmd;
    }

    /** 解析 engineHome：优先 EngineSubmitRef，否则环境变量 SEATUNNEL_HOME。 */
    static String resolveEngineHome(EngineSubmitRef ref) {
        if (ref != null && ref.engineHome() != null && !ref.engineHome().isBlank()) {
            return ref.engineHome();
        }
        return System.getenv("SEATUNNEL_HOME");
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
        // SeaTunnel 2.3.x requires JDK 17/21 (JDK 25+ removes javax.security.auth.Subject.getSubject
        // causing Hazelcast NPE in CheckpointService init). If caller sets JAVA_HOME to a compatible JDK,
        // prepend it to PATH so seatunnel.sh picks it up.
        String stJavaHome = pb.environment().get("JAVA_HOME");
        if (stJavaHome != null && !stJavaHome.isBlank()) {
            String path = pb.environment().getOrDefault("PATH", "");
            pb.environment().put("PATH", stJavaHome + "/bin:" + path);
        }
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new ExecutionResult(false, -1, "", "", false, false,
                    "[SEATUNNEL] 无法启动 seatunnel.sh: " + e.getMessage());
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
        }, "seatunnel-reader");
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
        Path tmp = Files.createTempFile("dw-seatunnel-", suffix);
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
