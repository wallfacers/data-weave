package com.dataweave.worker.infrastructure;

import com.dataweave.master.infrastructure.DriverJarStorage;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.ExecutionContext.SparkSubmitRef;
import com.dataweave.worker.domain.TaskExecutor.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SparkTaskExecutor 契约测试（contracts C1，FR-016）：三形态命令构造（可单测纯函数）+ SKIPPED 判定 +
 * fake spark-submit 透传（不依赖真 Spark，零依赖底线 D7）。
 */
class SparkTaskExecutorTest {

    private final SparkTaskExecutor executor = new SparkTaskExecutor();

    @Test
    void type_isSpark() {
        assertThat(executor.type()).isEqualTo("SPARK");
    }

    // ---- buildCommand pyspark (MVP) ----

    @Test
    void buildCommand_pyspark_masterDeployModeQueueConf_orderDeterministic() {
        SparkSubmitRef ref = ref("/opt/spark", "local[*]", "client", "etl",
                Map.of("spark.executor.memory", "2g", "spark.driver.memory", "1g"));
        List<String> cmd = SparkTaskExecutor.buildCommand(ref, "/tmp/body.py", "pyspark", null, null);

        assertThat(cmd.get(0)).isEqualTo("/opt/spark/bin/spark-submit");
        assertThat(cmd.get(idx(cmd, "--master") + 1)).isEqualTo("local[*]");
        assertThat(cmd.get(idx(cmd, "--deploy-mode") + 1)).isEqualTo("client");
        assertThat(cmd.get(idx(cmd, "--queue") + 1)).isEqualTo("etl");
        int c = idx(cmd, "--conf");
        assertThat(cmd.get(c + 1)).isEqualTo("spark.driver.memory=1g"); // TreeMap 字典序
        assertThat(cmd.get(c + 3)).isEqualTo("spark.executor.memory=2g");
        assertThat(cmd.get(cmd.size() - 1)).isEqualTo("/tmp/body.py");
    }

    @Test
    void buildCommand_omitsBlankDeployModeQueueAndEmptyConf() {
        SparkSubmitRef ref = ref("/opt/spark", "yarn", null, null, null);
        List<String> cmd = SparkTaskExecutor.buildCommand(ref, "/tmp/b.py", "pyspark", null, null);
        assertThat(cmd).containsExactly("/opt/spark/bin/spark-submit", "--master", "yarn", "/tmp/b.py");
    }

    // ---- buildCommand spark-sql (US3) ----

    @Test
    void buildCommand_sparkSql_addsRunnerAndBody() {
        SparkSubmitRef ref = ref("/spark", "yarn", null, null, null);
        List<String> cmd = SparkTaskExecutor.buildCommand(ref, "/tmp/body.sql", "spark-sql", null, "/tmp/runner.py");
        // spark-submit --master yarn /tmp/runner.py /tmp/body.sql
        assertThat(cmd.get(0)).isEqualTo("/spark/bin/spark-submit");
        assertThat(cmd.get(idx(cmd, "--master") + 1)).isEqualTo("yarn");
        // runner then body are the last two args
        int n = cmd.size();
        assertThat(cmd.get(n - 2)).isEqualTo("/tmp/runner.py");
        assertThat(cmd.get(n - 1)).isEqualTo("/tmp/body.sql");
    }

    // ---- buildCommand jar (US3) ----

    @Test
    void buildCommand_jar_addsClassAndJar() {
        SparkSubmitRef ref = ref("/spark", "yarn", "cluster", null, null);
        List<String> cmd = SparkTaskExecutor.buildCommand(ref, "/tmp/app.jar", "jar", "com.example.Main", null);
        assertThat(cmd.get(0)).isEqualTo("/spark/bin/spark-submit");
        assertThat(cmd.get(idx(cmd, "--master") + 1)).isEqualTo("yarn");
        assertThat(cmd.get(idx(cmd, "--deploy-mode") + 1)).isEqualTo("cluster");
        assertThat(cmd.get(idx(cmd, "--class") + 1)).isEqualTo("com.example.Main");
        assertThat(cmd.get(cmd.size() - 1)).isEqualTo("/tmp/app.jar");
    }

    @Test
    void buildCommand_jar_omitsClassWhenNull() {
        SparkSubmitRef ref = ref("/spark", "local[*]", null, null, null);
        List<String> cmd = SparkTaskExecutor.buildCommand(ref, "/tmp/app.jar", "jar", null, null);
        assertThat(cmd).doesNotContain("--class");
        assertThat(cmd.get(cmd.size() - 1)).isEqualTo("/tmp/app.jar");
    }

    // ---- 069 T018: 资源提示（driver/executor memory+cores）----

    @Test
    void buildCommand_withResourceHints_addsDriverExecutorMemoryAndCores() {
        SparkSubmitRef ref = new SparkSubmitRef("/opt/spark", "yarn", null, null, null,
                null, null, null, 4096, 2);
        List<String> cmd = SparkTaskExecutor.buildCommand(ref, "/tmp/b.py", "pyspark", null, null);
        assertThat(cmd.get(idx(cmd, "--driver-memory") + 1)).isEqualTo("4096m");
        assertThat(cmd.get(idx(cmd, "--executor-memory") + 1)).isEqualTo("4096m");
        assertThat(cmd.get(idx(cmd, "--driver-cores") + 1)).isEqualTo("2");
        assertThat(cmd.get(idx(cmd, "--executor-cores") + 1)).isEqualTo("2");
    }

    @Test
    void buildCommand_noResourceHints_omitsMemoryAndCoreFlags() {
        SparkSubmitRef ref = ref("/opt/spark", "yarn", null, null, null);
        List<String> cmd = SparkTaskExecutor.buildCommand(ref, "/tmp/b.py", "pyspark", null, null);
        assertThat(cmd).doesNotContain("--driver-memory", "--executor-memory", "--driver-cores", "--executor-cores");
    }

    // ---- SKIPPED 判定 ----

    @Test
    void skipReason_nullRef_skipped() {
        assertThat(SparkTaskExecutor.skipReason(null)).contains("已跳过");
    }

    @Test
    void skipReason_blankSparkHome_skipped() {
        assertThat(SparkTaskExecutor.skipReason(ref(null, "local[*]", null, null, null))).contains("SPARK_HOME");
    }

    @Test
    void skipReason_missingSubmitBinary_skipped() {
        assertThat(SparkTaskExecutor.skipReason(ref("/nonexistent", "local[*]", null, null, null)))
                .contains("spark-submit").contains("不存在");
    }

    @Test
    void skipReason_blankMaster_skipped(@TempDir Path tmp) throws IOException {
        Path home = fakeSparkHomeIn(tmp, "0");
        assertThat(SparkTaskExecutor.skipReason(ref(home.toString(), "", null, null, null))).contains("master");
    }

    @Test
    void skipReason_allPresent_returnsNull(@TempDir Path tmp) throws IOException {
        Path home = fakeSparkHomeIn(tmp, "0");
        assertThat(SparkTaskExecutor.skipReason(ref(home.toString(), "local[*]", null, null, null))).isNull();
    }

    // ---- execute ----

    @Test
    void execute_noSparkHome_skippedNotSuccess() {
        ExecutionResult r = executor.execute(sparkCtx("print('x')", ref(null, "local[*]", null, null, null)), l -> {});
        assertThat(r.skipped()).isTrue();
        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(0);
    }

    @Test
    void execute_fakeSparkSubmitSuccess_passesThroughExit0StdoutNotSkipped(@TempDir Path tmp) throws IOException {
        Path home = fakeSparkHomeIn(tmp, "0");
        ExecutionResult r = executor.execute(
                sparkCtx("print('ok')", ref(home.toString(), "local[*]", null, null, null)), l -> {});
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.success()).isTrue();
        assertThat(r.skipped()).isFalse();
        assertThat(r.stdout()).contains("fake-spark-out");
    }

    @Test
    void execute_fakeSparkSubmitFailure_passesThroughNonZeroNotSkipped(@TempDir Path tmp) throws IOException {
        Path home = fakeSparkHomeIn(tmp, "3");
        ExecutionResult r = executor.execute(
                sparkCtx("raise Exception", ref(home.toString(), "local[*]", null, null, null)), l -> {});
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.success()).isFalse();
        assertThat(r.skipped()).isFalse();
    }

    // ---- jar 形态：服务端存储下载（storage.get → temp jar → spark-submit；storage 无 key → 真实失败）----

    @Test
    void execute_jar_downloadedFromStorage_sparkSubmitPointsToTempJar(@TempDir Path tmp) throws IOException {
        Path home = fakeSparkHomeIn(tmp, "0");
        byte[] jarBytes = "fake-jar-content".getBytes();
        DriverJarStorage fakeStorage = new DriverJarStorage() {
            @Override public String put(String key, byte[] content) { return key; }
            @Override public byte[] get(String key) { return "my-app.jar".equals(key) ? jarBytes : null; }
            @Override public void delete(String key) { /* no-op */ }
            @Override public String type() { return "FAKE"; }
        };
        SparkTaskExecutor executorWithStorage = new SparkTaskExecutor(fakeStorage);

        SparkSubmitRef ref = new SparkSubmitRef(home.toString(), "local[*]", null, null, null,
                "jar", "my-app.jar", "com.example.Main");
        StringBuilder out = new StringBuilder();
        ExecutionResult r = executorWithStorage.execute(
                new ExecutionContext("", null, 1, 10, "TEST", "SPARK", null, null, null, ref),
                line -> out.append(line).append('\n'));

        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.skipped()).isFalse();
        assertThat(r.success()).isTrue();
        // 验证通过存储下载了 jar
        assertThat(out.toString()).contains("从资产存储下载 jar: my-app.jar");
        // 验证 spark-submit 指向临时 jar（非原始 storageKey）
        List<String> argv = out.toString().lines()
                .filter(l -> l.startsWith("ARG="))
                .map(l -> l.substring(4))
                .toList();
        assertThat(argv).anyMatch(a -> a.endsWith(".jar") && !a.equals("my-app.jar"));
        assertThat(argv).contains("--class", "com.example.Main");
    }

    @Test
    void execute_jar_storageMissingKey_realFailureNotSkipped(@TempDir Path tmp) throws IOException {
        Path home = fakeSparkHomeIn(tmp, "0");
        DriverJarStorage fakeStorage = new DriverJarStorage() {
            @Override public String put(String key, byte[] content) { return key; }
            @Override public byte[] get(String key) { return null; } // 永远返回 null
            @Override public void delete(String key) { /* no-op */ }
            @Override public String type() { return "FAKE"; }
        };
        SparkTaskExecutor executorWithStorage = new SparkTaskExecutor(fakeStorage);

        SparkSubmitRef ref = new SparkSubmitRef(home.toString(), "local[*]", null, null, null,
                "jar", "nonexistent.jar", "com.example.Main");
        ExecutionResult r = executorWithStorage.execute(
                new ExecutionContext("", null, 1, 10, "TEST", "SPARK", null, null, null, ref),
                l -> {});

        // 存储无此 key → 真实失败，非 SKIPPED（contracts C1.4）
        assertThat(r.skipped()).isFalse();
        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.message()).contains("jar 资产不存在").contains("nonexistent.jar");
    }

    @Test
    void execute_jar_localFileExists_usesDirectlyNotStorage(@TempDir Path tmp) throws IOException {
        Path home = fakeSparkHomeIn(tmp, "0");
        // 在 tmp 下创建一个真实 jar 文件
        Path localJar = tmp.resolve("local-app.jar");
        Files.writeString(localJar, "local-jar-content");

        // 用带 storage 的执行器（storage 也有同名 key 但不应被调用）
        DriverJarStorage fakeStorage = new DriverJarStorage() {
            @Override public String put(String key, byte[] content) { return key; }
            @Override public byte[] get(String key) { throw new AssertionError("storage.get 不应被调用（本地文件已存在）"); }
            @Override public void delete(String key) { /* no-op */ }
            @Override public String type() { return "FAKE"; }
        };
        SparkTaskExecutor executorWithStorage = new SparkTaskExecutor(fakeStorage);

        SparkSubmitRef ref = new SparkSubmitRef(home.toString(), "local[*]", null, null, null,
                "jar", localJar.toString(), "com.example.Main");
        StringBuilder out = new StringBuilder();
        ExecutionResult r = executorWithStorage.execute(
                new ExecutionContext("", null, 1, 10, "TEST", "SPARK", null, null, null, ref),
                line -> out.append(line).append('\n'));

        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.skipped()).isFalse();
        // 不应出现"从资产存储下载"日志（本地文件直接使用）
        assertThat(out.toString()).doesNotContain("从资产存储下载");
        // spark-submit 指向原始本地路径
        List<String> argv = out.toString().lines()
                .filter(l -> l.startsWith("ARG="))
                .map(l -> l.substring(4))
                .toList();
        assertThat(argv).contains(localJar.toString());
    }

    // ---- helpers ----

    private static int idx(List<String> cmd, String flag) {
        int i = cmd.indexOf(flag);
        if (i < 0) {
            throw new AssertionError("flag not in command: " + flag + " :: " + cmd);
        }
        return i;
    }

    private static SparkSubmitRef ref(String sparkHome, String master, String deployMode, String queue,
                                       Map<String, String> conf) {
        return new SparkSubmitRef(sparkHome, master, deployMode, queue, conf, null, null, null);
    }

    private static ExecutionContext sparkCtx(String content, SparkSubmitRef ref) {
        return new ExecutionContext(content, null, 1, 10, "TEST", "SPARK", null, null, null, ref);
    }

    // ---- doExecute spark-sql 真实命令（堵拼接缺陷：runner 与 body 须为独立 arg，非含空格拼接串）----

    @Test
    void execute_sparkSql_runnerAndBodyAreSeparateArgs_noConcat(@TempDir Path tmp) throws IOException {
        Path home = fakeSparkHomeIn(tmp, "0");
        SparkSubmitRef ref = new SparkSubmitRef(home.toString(), "local[*]", null, null, null,
                "spark-sql", null, null);
        StringBuilder out = new StringBuilder();
        ExecutionResult r = executor.execute(
                new ExecutionContext("select 1;", null, 1, 10, "TEST", "SPARK", null, null, null, ref),
                line -> out.append(line).append('\n'));

        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.skipped()).isFalse();
        List<String> argv = out.toString().lines()
                .filter(l -> l.startsWith("ARG="))
                .map(l -> l.substring(4))
                .toList();
        // 拼接 bug 会把 "runner body" 拼成含空格的单一 arg —— 断言无任何 arg 含空格
        assertThat(argv).as("无 arg 含空格（runner/body 未被拼接）").noneMatch(a -> a.contains(" "));
        // runner(.py) 恰一个、body(.sql) 恰一个，各自独立
        assertThat(argv).filteredOn(a -> a.endsWith(".py")).as("仅一个 runner .py").hasSize(1);
        assertThat(argv).filteredOn(a -> a.endsWith(".sql")).as("仅一个 body .sql").hasSize(1);
    }

    /**
     * 造假 ${dir}/bin/spark-submit：echo 一行标记 + 逐 arg 打印（{@code ARG=<value>}）+ exit ${code}，
     * 让执行器真起子进程验透传与真实命令构造（非真 Spark）。
     */
    private static Path fakeSparkHomeIn(Path dir, String exitCode) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path submit = bin.resolve("spark-submit");
        Files.writeString(submit, "#!/bin/bash\necho fake-spark-out\nfor a in \"$@\"; do echo \"ARG=$a\"; done\nexit "
                + exitCode + "\n");
        if (!submit.toFile().setExecutable(true)) {
            throw new IOException("cannot chmod spark-submit");
        }
        return dir;
    }
}
