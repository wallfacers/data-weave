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
 * Flink 任务执行器（FR-006）。
 *
 * <p>以子进程提交 Flink，与 {@link SparkTaskExecutor}/{@link DataXTaskExecutor}
 * 同构（ProcessBuilder → 逐行 {@code onLine} → {@code waitFor(timeout)} → {@code destroyForcibly}
 * → exitCode 忠实透传）。两种内容形态（sql / jar）由 {@link EngineSubmitRef#mode()}
 * 决定，经统一 {@link #buildCommand} 单一提交路径：
 * <ul>
 *   <li>sql → {@code sql-client.sh -f <file>}（内容写临时 .sql 文件）</li>
 *   <li>jar → {@code flink run [-c <mainClass>] <jar>}</li>
 * </ul>
 *
 * <p>环境缺失（无 FLINK_HOME / {@code bin/flink} 不可用）→
 * {@code ExecutionResult.skipped(String)}（FR-008/009），不伪装成功、不阻塞；
 * jar 资产缺失或 SQL 内容为空 → 真实失败（contracts C3.2，exitCode 忠实透传）。
 * 本地 {@code dw run} 与服务端经同一执行器零改动漂移（原则 III fidelity）。
 */
@Component
public class FlinkTaskExecutor extends AbstractTaskExecutor {

    private static final int MAX_CAPTURED_LINES = 5000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 3600;

    @Override
    public String type() {
        return "FLINK";
    }

    @Override
    protected ExecutionResult doExecute(ExecutionContext ctx, Consumer<String> onLine) throws Exception {
        EngineSubmitRef ref = ctx.engine();

        // SKIPPED 判定（contracts C3）
        String reason = skipReason(ref);
        if (reason != null) {
            emit(onLine, reason);
            return ExecutionResult.skipped(reason);
        }

        String mode = (ref.mode() != null && !ref.mode().isBlank()) ? ref.mode() : "sql";
        String content = ctx.content() == null ? "" : ctx.content().replace("\r\n", "\n").replace('\r', '\n');

        Path sqlFile = null;
        try {
            String submitTarget;
            switch (mode) {
                case "sql" -> {
                    if (content.isBlank()) {
                        String msg = "Flink SQL 内容为空";
                        emit(onLine, msg);
                        return new ExecutionResult(false, -1, "", "", false, false, msg);
                    }
                    sqlFile = writeTempFile(content, ".sql");
                    submitTarget = sqlFile.toString();
                }
                case "jar" -> {
                    // jar 形态：ref.jarPath() 为本地文件路径（dw run）；服务端经资产存储下载（本期同 Spark jar 范式，预留）。
                    String jarPath = ref.jarPath();
                    if (jarPath == null || jarPath.isBlank()) {
                        String msg = "jar 资产未指定（请配置 jarPath）";
                        emit(onLine, msg);
                        return new ExecutionResult(false, -1, "", "", false, false, msg);
                    }
                    if (!Files.exists(Path.of(jarPath))) {
                        String msg = "jar 资产不存在或不可读: " + jarPath + "（请确认 jar 路径正确）";
                        emit(onLine, msg);
                        return new ExecutionResult(false, -1, "", "", false, false, msg);
                    }
                    submitTarget = jarPath;
                }
                default -> {
                    String msg = "不支持的 flinkMode: " + mode + "（支持 sql / jar）";
                    emit(onLine, msg);
                    return new ExecutionResult(false, -1, "", "", false, false, msg);
                }
            }

            List<String> command = buildCommand(resolveEngineHome(ref), mode, submitTarget, ref.mainClass());
            return runSubprocess(command, ctx, onLine);
        } finally {
            if (sqlFile != null) {
                cleanup(sqlFile);
            }
        }
    }

    // ---- public static 方法（可单测，无副作用）----

    /**
     * SKIPPED 触发判定（contracts C3）。返回非 null = 跳过原因，null = 可执行。
     * 检查 FLINK_HOME 与 {@code bin/flink}（Flink 主入口）；sql-client.sh 缺失属运行时错误（真实失败），非 SKIPPED。
     */
    static String skipReason(EngineSubmitRef ref) {
        String home = resolveEngineHome(ref);
        if (home == null || home.isBlank()) {
            return "已跳过：本地无 Flink 环境（FLINK_HOME 未配置）";
        }
        Path flinkBin = Path.of(home, "bin", "flink");
        if (!Files.exists(flinkBin)) {
            return "已跳过：本地无 Flink 环境（" + flinkBin + " 不存在）";
        }
        return null;
    }

    /**
     * 构造 Flink 提交命令（可单测纯函数，contracts C4）。
     *
     * @param engineHome   FLINK_HOME 路径
     * @param mode         sql | jar（null 默认 sql）
     * @param submitTarget sql: .sql 文件路径 / jar: app.jar 路径
     * @param mainClass    jar 形态的 {@code -c} 主类（其它 null）
     */
    static List<String> buildCommand(String engineHome, String mode, String submitTarget, String mainClass) {
        List<String> cmd = new ArrayList<>();
        switch (mode != null ? mode : "sql") {
            case "jar" -> {
                cmd.add(Path.of(engineHome, "bin", "flink").toString());
                cmd.add("run");
                if (mainClass != null && !mainClass.isBlank()) {
                    cmd.add("-c");
                    cmd.add(mainClass);
                }
                cmd.add(submitTarget);
            }
            default -> { // sql
                cmd.add(Path.of(engineHome, "bin", "sql-client.sh").toString());
                cmd.add("-f");
                cmd.add(submitTarget);
            }
        }
        return cmd;
    }

    /** 解析 engineHome：优先 EngineSubmitRef，否则环境变量 FLINK_HOME。 */
    static String resolveEngineHome(EngineSubmitRef ref) {
        if (ref != null && ref.engineHome() != null && !ref.engineHome().isBlank()) {
            return ref.engineHome();
        }
        return System.getenv("FLINK_HOME");
    }

    // ---- 子进程执行（复用 Spark/DataX/Shell 范式）----

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
                    "[FLINK] 无法启动 flink: " + e.getMessage());
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
        }, "flink-reader");
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
        Path tmp = Files.createTempFile("dw-flink-", suffix);
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
