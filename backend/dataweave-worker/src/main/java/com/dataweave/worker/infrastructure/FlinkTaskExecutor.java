package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.AbstractTaskExecutor;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.ExecutionContext.EngineSubmitRef;
import com.dataweave.worker.domain.ExternalJobHandleWriter;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flink 任务执行器（FR-006 / 060 FR-022~026）。
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
 * <p><b>外部托管长驻作业（long_running，060 FR-022~026）</b>：
 * <ul>
 *   <li>detached 提交：{@code flink run -d ...}，解析 JobID 写回 external_job_handle</li>
 *   <li>轮询模式：不阻塞 waitFor，改为按 JobID 轮询 Flink REST 驱动状态</li>
 *   <li>reattach：实例 external_job_handle 非空 → 直接轮询，不 flink run（FR-024）</li>
 *   <li>豁免 timeout（不套 DEFAULT_TIMEOUT_SECONDS）与 worker 自我中止（FR-025/026）</li>
 * </ul>
 *
 * <p><b>有界 Flink（long_running=false）exit-code/stdout 语义不变</b>（constitution III 保真）：
 * 阻塞子进程 + waitFor(timeout) + destroyForcibly → exitCode 忠实透传。
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

    /** Flink detached 提交后 stdout 中的 JobID 模式：JobID: <hex>（32 hex chars，大小写不敏感）。 */
    private static final Pattern JOB_ID_PATTERN = Pattern.compile(
            "JobID:\\s*([0-9a-fA-F]{32})");

    private final ExternalJobHandleWriter handleWriter;

    public FlinkTaskExecutor() {
        this(ExternalJobHandleWriter.noop());
    }

    /** 可注入 ExternalJobHandleWriter（测试用；生产由 Spring 构造后 set）。 */
    FlinkTaskExecutor(ExternalJobHandleWriter handleWriter) {
        this.handleWriter = handleWriter;
    }

    // ---- FlinkTaskExecutor 专属（测试/生产注入）----

    void setHandleWriter(ExternalJobHandleWriter w) {
        // 仅用于测试替换桩，生产由 Spring 管理
    }

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

        // ── reattach 路径：external_job_handle 非空 → 按句柄重连（FR-024）──
        if (ref.externalJobHandle() != null && !ref.externalJobHandle().isBlank()) {
            emit(onLine, "[FLINK] external_job_handle 非空，进入 reattach 模式（不重新提交）");
            emit(onLine, "[FLINK] handle=" + ref.externalJobHandle());
            // TODO(060-Foundational): 实现 Flink REST 轮询 reattach。
            // 当前桩：回显句柄并返回 skipped（不阻塞，不误报成功/失败）。
            // 真集成时改为：解析 handle → GET /jobs/{jobId} → 驱动 RUNNING/续约/终态。
            return ExecutionResult.skipped(
                    "[FLINK] reattach 模式（桩）：handle=" + ref.externalJobHandle());
        }

        // ── long_running 分支：detached 提交（FR-023）──
        if (ref.longRunning()) {
            return executeLongRunning(ctx, ref, mode, content, onLine);
        }

        // ── 有界/批 Flink：阻塞子进程，exit-code/stdout 语义不变（constitution III）──
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

            List<String> command = buildCommand(resolveEngineHome(ref), mode, submitTarget, ref.mainClass(), false);
            return runSubprocess(command, ctx, onLine);
        } finally {
            if (sqlFile != null) {
                cleanup(sqlFile);
            }
        }
    }

    /**
     * long_running 执行路径：detached 提交 + 解析 JobID + 写回句柄 + 轮询（FR-023/025）。
     *
     * <p>当前状态：桩实现——detached 提交和 JobID 解析逻辑完整，但 Flink REST 轮询
     * 待 Foundational 完成后集成。桩版本返回 skipped（不阻塞、不误报成功/失败）。
     */
    private ExecutionResult executeLongRunning(ExecutionContext ctx, EngineSubmitRef ref,
                                                String mode, String content, Consumer<String> onLine)
            throws Exception {
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
                    String jarPath = ref.jarPath();
                    if (jarPath == null || jarPath.isBlank()) {
                        String msg = "jar 资产未指定（请配置 jarPath）";
                        emit(onLine, msg);
                        return new ExecutionResult(false, -1, "", "", false, false, msg);
                    }
                    if (!Files.exists(Path.of(jarPath))) {
                        String msg = "jar 资产不存在或不可读: " + jarPath;
                        emit(onLine, msg);
                        return new ExecutionResult(false, -1, "", "", false, false, msg);
                    }
                    submitTarget = jarPath;
                }
                default -> {
                    String msg = "不支持的 flinkMode: " + mode;
                    emit(onLine, msg);
                    return new ExecutionResult(false, -1, "", "", false, false, msg);
                }
            }

            // detached 提交：flink run -d ...
            List<String> command = buildCommand(resolveEngineHome(ref), mode, submitTarget,
                    ref.mainClass(), true);

            emit(onLine, "[FLINK] long_running detached 提交: " + String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取 stdout 捕获 JobID
            List<String> captured = new ArrayList<>();
            boolean[] truncatedHolder = {false};
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
            }
            process.waitFor(30, TimeUnit.SECONDS); // detached 提交应很快返回

            String stdout = String.join("\n", captured);

            // 解析 JobID
            String jobId = parseJobId(stdout);
            if (jobId == null) {
                // detached 提交失败：无法解析 JobID → 判定为业务失败
                String msg = "Flink long_running 提交失败：无法解析 JobID。stdout:\n" + stdout;
                emit(onLine, msg);
                return new ExecutionResult(false, process.exitValue(), stdout, "", false, false, msg);
            }

            emit(onLine, "[FLINK] JobID=" + jobId);

            // 构造外部作业句柄
            String handle = "{\"jobId\":\"" + jobId + "\",\"restEndpoint\":\""
                    + resolveRestEndpoint(ref) + "\"}";
            emit(onLine, "[FLINK] external_job_handle=" + handle);
            // TODO(060-Foundational): 从 WorkerExecService 层调用 handleWriter.write(instanceId, handle)
            // instanceId 由 WorkerExecService.doRun 持有，通过新增的 TaskExecutor 回调传入。

            // TODO(060-Foundational): 实现 Flink REST 轮询（替换下面的 skipped 返回）。
            // 轮询逻辑：GET {restEndpoint}/jobs/{jobId} → 状态 RUNNING → 持续轮询+续约；
            // 终态（FINISHED/FAILED/CANCELED）→ 映射到 task_instance 终态。
            // 当前桩：返回 skipped（不阻塞、不误报成功/失败）。
            return ExecutionResult.skipped(
                    "[FLINK] long_running 已提交（桩，轮询待 Foundational）：JobID=" + jobId);
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
     * @param detached     true=flink run -d（long_running）；false=阻塞模式（有界/批）
     */
    static List<String> buildCommand(String engineHome, String mode, String submitTarget,
                                      String mainClass, boolean detached) {
        List<String> cmd = new ArrayList<>();
        switch (mode != null ? mode : "sql") {
            case "jar" -> {
                cmd.add(Path.of(engineHome, "bin", "flink").toString());
                cmd.add("run");
                if (detached) {
                    cmd.add("-d");
                }
                if (mainClass != null && !mainClass.isBlank()) {
                    cmd.add("-c");
                    cmd.add(mainClass);
                }
                cmd.add(submitTarget);
            }
            default -> { // sql
                cmd.add(Path.of(engineHome, "bin", "sql-client.sh").toString());
                if (detached) {
                    cmd.add("-d");
                }
                cmd.add("-f");
                cmd.add(submitTarget);
            }
        }
        return cmd;
    }

    /** 向后兼容：不带 detached 参数的 buildCommand（有界/批 Flink，语义不变）。 */
    static List<String> buildCommand(String engineHome, String mode, String submitTarget, String mainClass) {
        return buildCommand(engineHome, mode, submitTarget, mainClass, false);
    }

    /**
     * 从 Flink detached 提交 stdout 解析 JobID。
     *
     * @param stdout 子进程 stdout（完整输出）
     * @return 32-字符 hex JobID，未找到则 null
     */
    static String parseJobId(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        Matcher m = JOB_ID_PATTERN.matcher(stdout);
        return m.find() ? m.group(1) : null;
    }

    /** 解析 engineHome：优先 EngineSubmitRef，否则环境变量 FLINK_HOME。 */
    static String resolveEngineHome(EngineSubmitRef ref) {
        if (ref != null && ref.engineHome() != null && !ref.engineHome().isBlank()) {
            return ref.engineHome();
        }
        return System.getenv("FLINK_HOME");
    }

    /** 从 EngineSubmitRef.props 解析 REST endpoint，回退默认 localhost:8081。 */
    private static String resolveRestEndpoint(EngineSubmitRef ref) {
        if (ref != null && ref.props() != null) {
            String ep = ref.props().get("restEndpoint");
            if (ep != null && !ep.isBlank()) return ep;
        }
        return "http://localhost:8081";
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
