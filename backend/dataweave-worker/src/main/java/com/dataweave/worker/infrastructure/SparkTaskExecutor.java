package com.dataweave.worker.infrastructure;

import com.dataweave.master.infrastructure.DriverJarStorage;
import com.dataweave.worker.domain.AbstractTaskExecutor;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.ExecutionContext.SparkSubmitRef;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Spark 任务执行器（FR-001/002/003）。
 *
 * <p>以 {@code spark-submit} 子进程提交，与 {@link ShellTaskExecutor}/{@link PythonTaskExecutor}
 * 同构（ProcessBuilder → 逐行 {@code onLine} → {@code waitFor(timeout)} → {@code destroyForcibly}
 * → exitCode 忠实透传）。三种内容形态（pyspark / spark-sql / jar）由 {@link SparkSubmitRef#sparkMode()}
 * 或执行时默认推断，经统一 {@link #buildCommand} 单一提交路径（决策 A1）。
 *
 * <p>环境缺失（无 SPARK_HOME / {@code spark-submit} 不可用 / master 空）→
 * {@code ExecutionResult.skipped(String)}（FR-008），不伪装成功、不阻塞；
 * 作业自身失败 → exitCode 忠实透传（FR-001）。jar 资产缺失 → 真实失败（非 SKIPPED，contracts C1.4）。
 * 本地 {@code local[*]} 与服务端 {@code yarn} 经同一执行器零改动漂移（原则 III fidelity）。
 */
@Component
public class SparkTaskExecutor extends AbstractTaskExecutor {

    private static final int MAX_CAPTURED_LINES = 5000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 3600;

    private final DriverJarStorage storage;

    /** Spring 注入（服务端）：DriverJarStorage 由 master 模块提供（MinIO/LOCAL 后端）。 */
    public SparkTaskExecutor(DriverJarStorage storage) {
        this.storage = storage;
    }

    /** 无参构造（本地 dw run / 测试）：storage=null，jar 形态走本地文件路径。 */
    public SparkTaskExecutor() {
        this.storage = null;
    }

    @Override
    public String type() {
        return "SPARK";
    }

    @Override
    protected ExecutionResult doExecute(ExecutionContext ctx, Consumer<String> onLine) throws Exception {
        SparkSubmitRef ref = ctx.spark();

        // SKIPPED 判定（contracts C1.3）
        String reason = skipReason(ref);
        if (reason != null) {
            emit(onLine, reason);
            return ExecutionResult.skipped(reason);
        }

        String mode = ref.sparkMode() != null && !ref.sparkMode().isBlank() ? ref.sparkMode() : "pyspark";
        String content = ctx.content() == null ? "" : ctx.content().replace("\r\n", "\n").replace('\r', '\n');

        Path scriptFile = null;
        Path sqlRunner = null;
        Path downloadedJar = null;
        try {
            String submitTarget;
            switch (mode) {
                case "pyspark" -> {
                    scriptFile = writeTempScript(content, ".py");
                    submitTarget = scriptFile.toString();
                }
                case "spark-sql" -> {
                    scriptFile = writeTempScript(content, ".sql");
                    sqlRunner = extractSqlRunner();
                    // submitTarget = body.sql（干净路径）；runner 由 buildCommand 的 sqlRunnerPath 参数加入，
                    // 避免拼成含空格的单一 arg + runner 重复（修复 spark-sql 真跑命令构造缺陷）。
                    submitTarget = scriptFile.toString();
                }
                case "jar" -> {
                    // jar 形态：本地 dw run → 绝对/相对文件路径；服务端 → jarRef 为 storageKey，需下载。
                    String jarPath = ref.jarPath();
                    if (jarPath == null || jarPath.isBlank()) {
                        String msg = "jar 资产未指定（请配置 jarRef 或 jarPath）";
                        emit(onLine, msg);
                        return new ExecutionResult(false, -1, "", "", false, false, msg);
                    }
                    Path localPath = Path.of(jarPath);
                    if (Files.exists(localPath)) {
                        // 本地文件路径（dw run），直接使用
                        submitTarget = jarPath;
                    } else if (storage != null) {
                        // 服务端：jarPath 为 storageKey，从资产存储下载（复用 IsolatedDriverLoader 下载范式）
                        byte[] bytes = storage.get(jarPath);
                        if (bytes != null) {
                            downloadedJar = Files.createTempFile("dw-spark-jar-", ".jar");
                            Files.write(downloadedJar, bytes);
                            submitTarget = downloadedJar.toString();
                            emit(onLine, "从资产存储下载 jar: " + jarPath);
                        } else {
                            String msg = "jar 资产不存在: " + jarPath + "（存储中无此 key，请确认资产已上传）";
                            emit(onLine, msg);
                            return new ExecutionResult(false, -1, "", "", false, false, msg);
                        }
                    } else {
                        String msg = "jar 资产不存在或不可读: " + jarPath + "（请确认 jar 路径正确）";
                        emit(onLine, msg);
                        return new ExecutionResult(false, -1, "", "", false, false, msg);
                    }
                }
                default -> {
                    String msg = "不支持的 sparkMode: " + mode + "（支持 pyspark / spark-sql / jar）";
                    emit(onLine, msg);
                    return new ExecutionResult(false, -1, "", "", false, false, msg);
                }
            }

            return runSparkSubmit(buildCommand(ref, submitTarget, mode, ref.mainClass(),
                    sqlRunner != null ? sqlRunner.toString() : null), ctx, onLine);
        } finally {
            if (scriptFile != null) {
                cleanup(scriptFile);
            }
            if (sqlRunner != null) {
                cleanup(sqlRunner);
            }
            if (downloadedJar != null) {
                cleanup(downloadedJar);
            }
        }
    }

    // ---- public static 方法（可单测，无副作用）----

    /**
     * SKIPPED 触发判定（contracts C1.3）。返回非 null = 跳过原因，null = 可执行。
     */
    static String skipReason(SparkSubmitRef ref) {
        if (ref == null) {
            return "已跳过：本地无 Spark 环境（未绑定 SPARK 数据源）";
        }
        if (ref.sparkHome() == null || ref.sparkHome().isBlank()) {
            return "已跳过：本地无 Spark 环境（SPARK_HOME 未配置）";
        }
        Path submit = Path.of(ref.sparkHome(), "bin", "spark-submit");
        if (!Files.exists(submit)) {
            return "已跳过：本地无 Spark 环境（" + submit + " 不存在）";
        }
        if (ref.master() == null || ref.master().isBlank()) {
            return "已跳过：本地无 Spark 环境（master 未配置）";
        }
        return null;
    }

    /**
     * 构造 spark-submit 命令（可单测纯函数，contracts C1.1，不依赖真 Spark）。
     *
     * @param ref            spark 提交配置
     * @param submitTarget   pyspark: body.py 路径 / spark-sql: (未直接用，尾参由 runner+body 构成) / jar: app.jar 路径
     * @param sparkMode      pyspark | spark-sql | jar
     * @param mainClass      jar 形态的 --class（其它 null）
     * @param sqlRunnerPath  spark-sql 形态的 runner.py 路径（其它 null）
     */
    static List<String> buildCommand(SparkSubmitRef ref, String submitTarget, String sparkMode,
                                      String mainClass, String sqlRunnerPath) {
        String submit = Path.of(ref.sparkHome(), "bin", "spark-submit").toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(submit);
        addSubmitConf(cmd, ref);
        switch (sparkMode != null ? sparkMode : "pyspark") {
            case "spark-sql" -> {
                if (sqlRunnerPath != null) cmd.add(sqlRunnerPath);
                cmd.add(submitTarget);
            }
            case "jar" -> {
                if (mainClass != null && !mainClass.isBlank()) {
                    cmd.add("--class");
                    cmd.add(mainClass);
                }
                cmd.add(submitTarget);
            }
            default -> cmd.add(submitTarget); // pyspark
        }
        return cmd;
    }

    /** 展开 --master / --deploy-mode / --queue / --conf（顺序确定，便于断言）。 */
    private static void addSubmitConf(List<String> cmd, SparkSubmitRef ref) {
        if (ref.master() != null && !ref.master().isBlank()) {
            cmd.add("--master");
            cmd.add(ref.master());
        }
        if (ref.deployMode() != null && !ref.deployMode().isBlank()) {
            cmd.add("--deploy-mode");
            cmd.add(ref.deployMode());
        }
        if (ref.queue() != null && !ref.queue().isBlank()) {
            cmd.add("--queue");
            cmd.add(ref.queue());
        }
        if (ref.conf() != null && !ref.conf().isEmpty()) {
            new TreeMap<>(ref.conf()).forEach((k, v) -> {
                cmd.add("--conf");
                cmd.add(k + "=" + v);
            });
        }
    }

    // ---- 子进程执行（复用 Shell/Python 范式）----

    private ExecutionResult runSparkSubmit(List<String> command, ExecutionContext ctx, Consumer<String> onLine)
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
                    "[SPARK] 无法启动 spark-submit: " + e.getMessage());
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
        }, "spark-reader");
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

    /** 从 classpath 释放 spark/sql_runner.py 到临时文件（spark-sql 形态用）。 */
    private static Path extractSqlRunner() throws IOException {
        InputStream in = SparkTaskExecutor.class.getResourceAsStream("/spark/sql_runner.py");
        if (in == null) {
            throw new IOException("sql_runner.py not found in classpath (/spark/sql_runner.py)");
        }
        Path tmp = Files.createTempFile("dw-sql-runner-", ".py");
        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        return tmp;
    }

    private static Path writeTempScript(String content, String suffix) throws IOException {
        Path tmp = Files.createTempFile("dw-spark-", suffix);
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
