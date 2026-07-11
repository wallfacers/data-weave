package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.AbstractTaskExecutor;
import com.dataweave.worker.domain.CurrentExecution;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.ExecutionContext.EngineSubmitRef;
import com.dataweave.worker.domain.ExternalJobHandleWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    /** Flink detached 提交后 stdout 中的 JobID 模式。
     * 兼容两种格式：{@code flink run -d} 输出 {@code JobID: <hex>}（紧凑），
     * 以及 {@code sql-client.sh} 输出 {@code Job ID: <hex>}（带空格）。
     * 32 hex chars，大小写不敏感。 */
    private static final Pattern JOB_ID_PATTERN = Pattern.compile(
            "Job\\s*ID[=:]\\s*([0-9a-fA-F]{32})");

    private final ExternalJobHandleWriter handleWriter;

    /** Flink 作业状态抓取（可测试注入；默认真 HTTP）。 */
    FlinkJobStatusFetcher statusFetcher = FlinkJobStatusFetcher.http();

    /** long_running 轮询间隔（ms，可配；测试注入小值避免慢测）。 */
    @Value("${scheduler.flink.poll-interval-ms:5000}")
    long pollIntervalMs = 5000;

    /** 轮询连续失败上限 → 判失败交兜底（可配）。 */
    @Value("${scheduler.flink.max-poll-errors:60}")
    int maxPollErrors = 60;

    /** 本地 {@code dw run}/localrun 用：无 Spring，句柄回写 no-op（本地无 master）。 */
    public FlinkTaskExecutor() {
        this(ExternalJobHandleWriter.noop());
    }

    /** Spring 注入 {@link HttpExternalJobHandleWriter}（生产回写 external_job_handle 到 master）。 */
    @Autowired
    public FlinkTaskExecutor(ExternalJobHandleWriter handleWriter) {
        this.handleWriter = handleWriter;
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
            return executeReattach(ctx, ref, mode, content, onLine);
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
     * <p>完整实现（060 已去桩，061 真跑验证通过 SC-005）：
     * <ol>
     *   <li>detached 提交（flink run -d / sql-client.sh -d），解析 stdout 中的真实 JobID</li>
     *   <li>构造 JSON 句柄（jobId + restEndpoint）→ 回写 master（{@link #writeHandle}）</li>
     *   <li>REST 轮询（{@link FlinkJobStatusFetcher#http()}）GET /jobs/{jobId} → 解析
     *       {@code "state"} 字段 → 终态（FINISHED=success / FAILED/CANCELED=failure）</li>
     *   <li>reattach 路径（{@link #executeReattach}）：实例带 external_job_handle → 先探
     *       集群侧作业存在性 → 存在则直接轮询不重复提交，不存在则回退重新提交</li>
     * </ol>
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
            // 提交成功即回写 master（failover reattach / 人工 kill cancel 的依据）——须在轮询前，
            // 这样即使本 worker 随即宕机，master 也已持有句柄可 reattach 而非重复提交（FR-023/024）。
            writeHandle(handle, onLine);

            // 轮询 Flink REST 驱动状态，直至终态（不套 DEFAULT_TIMEOUT_SECONDS；FR-025）。
            return pollUntilTerminal(resolveRestEndpoint(ref), jobId, onLine);
        } finally {
            if (sqlFile != null) {
                cleanup(sqlFile);
            }
        }
    }

    /**
     * reattach 执行路径（FR-024）：实例已有 external_job_handle → 先探测集群侧作业是否存在，
     * 存在则直接轮询驱动状态（<b>不 flink run，不重复提交</b>）；不存在（集群侧已消失）则按业务重试
     * 重新 detached 提交（回到 {@link #executeLongRunning}，写新句柄）。
     */
    private ExecutionResult executeReattach(ExecutionContext ctx, EngineSubmitRef ref,
                                            String mode, String content, Consumer<String> onLine)
            throws Exception {
        String handle = ref.externalJobHandle();
        String jobId = parseHandleField(handle, "jobId");
        String restEndpoint = parseHandleField(handle, "restEndpoint");
        if (jobId == null || restEndpoint == null || restEndpoint.isBlank()) {
            restEndpoint = (restEndpoint == null || restEndpoint.isBlank()) ? resolveRestEndpoint(ref) : restEndpoint;
        }
        emit(onLine, "[FLINK] reattach 模式：handle=" + handle);
        if (jobId == null) {
            emit(onLine, "[FLINK] 句柄无 jobId，回退重新提交");
            return executeLongRunning(ctx, ref, mode, content, onLine);
        }
        // 探测作业是否存在
        String state;
        try {
            state = statusFetcher.fetchState(restEndpoint, jobId);
        } catch (IOException e) {
            emit(onLine, "[FLINK] reattach 探测失败（" + e.getMessage() + "），进入轮询等待恢复");
            return pollUntilTerminal(restEndpoint, jobId, onLine);
        }
        if (state == null) {
            emit(onLine, "[FLINK] 集群侧作业不存在（JobID=" + jobId + "），按业务重试重新提交");
            return executeLongRunning(ctx, ref, mode, content, onLine);
        }
        emit(onLine, "[FLINK] reattach 命中：JobID=" + jobId + " 当前状态=" + state + "，继续监控（不重新提交）");
        return pollUntilTerminal(restEndpoint, jobId, onLine);
    }

    /**
     * 轮询 Flink REST 直至作业终态（FR-025）。可中断（worker 优雅停机）；连续抓取失败超上限判失败交兜底。
     * 状态映射：FINISHED→success；FAILED/FAILING→failure；CANCELED/CANCELLING→failure（人工 kill 已置 STOPPED，
     * 此失败不覆盖终态，benign）；其余（RUNNING/RESTARTING/CREATED/…）→ 继续轮询。
     */
    private ExecutionResult pollUntilTerminal(String restEndpoint, String jobId, Consumer<String> onLine) {
        int consecutiveErrors = 0;
        while (!Thread.currentThread().isInterrupted()) {
            String state;
            try {
                state = statusFetcher.fetchState(restEndpoint, jobId);
                consecutiveErrors = 0;
            } catch (IOException e) {
                consecutiveErrors++;
                emit(onLine, "[FLINK] 轮询失败(" + consecutiveErrors + "/" + maxPollErrors + "): " + e.getMessage());
                if (consecutiveErrors >= maxPollErrors) {
                    return new ExecutionResult(false, -1, "", "", false, false,
                            "[FLINK] REST 轮询连续失败 " + consecutiveErrors + " 次，判失败交兜底 JobID=" + jobId);
                }
                if (!sleepPoll()) break;
                continue;
            }
            if (state == null) {
                return new ExecutionResult(false, -1, "", "", false, false,
                        "[FLINK] 作业不存在（JobID=" + jobId + "）");
            }
            switch (state.toUpperCase()) {
                case "FINISHED" -> {
                    emit(onLine, "[FLINK] 作业完成 JobID=" + jobId);
                    return new ExecutionResult(true, 0, "", "", false, false, "[FLINK] 作业完成");
                }
                case "FAILED", "FAILING" -> {
                    return new ExecutionResult(false, 1, "", "", false, false,
                            "[FLINK] 作业失败 state=" + state + " JobID=" + jobId);
                }
                case "CANCELED", "CANCELLING" -> {
                    return new ExecutionResult(false, -1, "", "", false, false,
                            "[FLINK] 作业已取消 state=" + state + " JobID=" + jobId);
                }
                default -> {
                    emit(onLine, "[FLINK] state=" + state + "，继续轮询 JobID=" + jobId);
                    if (!sleepPoll()) break;
                }
            }
        }
        return new ExecutionResult(false, -1, "", "", false, false,
                "[FLINK] 轮询被中断（worker 停机）JobID=" + jobId);
    }

    /** 轮询间隔休眠；被中断返回 false（触发轮询循环退出）。 */
    private boolean sleepPoll() {
        try {
            Thread.sleep(pollIntervalMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 通过绑定的实例 id 回写 external_job_handle 到 master（best-effort，失败不断执行）。 */
    private void writeHandle(String handle, Consumer<String> onLine) {
        UUID instanceId = CurrentExecution.currentInstanceId();
        if (instanceId == null) {
            emit(onLine, "[FLINK] 无实例绑定（本地 dw run？），external_job_handle 未回写");
            return;
        }
        try {
            handleWriter.write(instanceId, handle);
            emit(onLine, "[FLINK] external_job_handle 已回写 instance=" + instanceId);
        } catch (Exception e) {
            emit(onLine, "[FLINK] external_job_handle 回写失败: " + e.getMessage());
        }
    }

    /** 从句柄 JSON 提取字段（jobId/restEndpoint）——正则，零 Jackson 依赖。 */
    static String parseHandleField(String handleJson, String field) {
        if (handleJson == null) return null;
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(handleJson);
        return m.find() ? m.group(1) : null;
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
                // 不传 -d：Flink 1.20 sql-client.sh 的 -d 是 --define（会话变量），
                // 而非 detached（仅 flink run 支持 -d）。sql-client.sh -f 本身对
                // streaming query 提交后即返回，JobID 在 stdout 中可解析（061 真跑验证）。
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
