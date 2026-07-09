package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.ExecutionContext.EngineSubmitRef;
import com.dataweave.worker.domain.TaskExecutor.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FlinkTaskExecutor 契约测试（contracts C4/C6）：buildCommand 纯函数（sql/jar 双形态）+
 * SKIPPED 判定 + fake flink 透传 + jar 缺失失败 + 超时断言（不依赖真 Flink，零依赖底线）。
 */
class FlinkTaskExecutorTest {

    private final FlinkTaskExecutor executor = new FlinkTaskExecutor();

    @Test
    void type_isFlink() {
        assertThat(executor.type()).isEqualTo("FLINK");
    }

    // ---- buildCommand ----

    @Test
    void buildCommand_sqlUsesSqlClientShF() {
        List<String> cmd = FlinkTaskExecutor.buildCommand("/opt/flink", "sql", "/tmp/q.sql", null);
        assertThat(cmd).containsExactly("/opt/flink/bin/sql-client.sh", "-f", "/tmp/q.sql");
    }

    @Test
    void buildCommand_jarWithMainClass() {
        List<String> cmd = FlinkTaskExecutor.buildCommand("/opt/flink", "jar", "/tmp/app.jar", "com.example.Main");
        assertThat(cmd).containsExactly("/opt/flink/bin/flink", "run", "-c", "com.example.Main", "/tmp/app.jar");
    }

    @Test
    void buildCommand_jarWithoutMainClassOmitsC() {
        List<String> cmd = FlinkTaskExecutor.buildCommand("/opt/flink", "jar", "/tmp/app.jar", null);
        assertThat(cmd).containsExactly("/opt/flink/bin/flink", "run", "/tmp/app.jar");
    }

    @Test
    void buildCommand_nullModeDefaultsToSql() {
        List<String> cmd = FlinkTaskExecutor.buildCommand("/opt/flink", null, "/tmp/q.sql", null);
        assertThat(cmd.get(0)).isEqualTo("/opt/flink/bin/sql-client.sh");
        assertThat(cmd).contains("-f", "/tmp/q.sql");
    }

    // ---- SKIPPED 判定 ----

    @Test
    void skipReason_nullRef_skipped() {
        assertThat(FlinkTaskExecutor.skipReason(null)).contains("已跳过").contains("FLINK_HOME");
    }

    @Test
    void skipReason_nullEngineHome_skipped() {
        EngineSubmitRef ref = new EngineSubmitRef("FLINK", null, null, null, null, null, null);
        assertThat(FlinkTaskExecutor.skipReason(ref)).contains("FLINK_HOME");
    }

    @Test
    void skipReason_missingFlinkBin_skipped() {
        EngineSubmitRef ref = new EngineSubmitRef("FLINK", "/nonexistent/flink/home", null, null, null, null, null);
        assertThat(FlinkTaskExecutor.skipReason(ref)).contains("flink").contains("不存在");
    }

    @Test
    void skipReason_allPresent_returnsNull(@TempDir Path tmp) throws IOException {
        Path home = fakeFlinkHomeIn(tmp);
        EngineSubmitRef ref = new EngineSubmitRef("FLINK", home.toString(), null, null, null, null, null);
        assertThat(FlinkTaskExecutor.skipReason(ref)).isNull();
    }

    // ---- execute: sql 形态 ----

    @Test
    void execute_noFlinkHome_skippedNotSuccess() {
        EngineSubmitRef ref = new EngineSubmitRef("FLINK", null, "sql", null, null, null, null);
        ExecutionResult r = executor.execute(flinkCtx("SELECT 1", ref), l -> {});
        assertThat(r.skipped()).isTrue();
        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(0);
    }

    @Test
    void execute_blankSql_realFailureNotSkipped(@TempDir Path tmp) throws IOException {
        Path home = fakeFlinkHomeIn(tmp);
        EngineSubmitRef ref = new EngineSubmitRef("FLINK", home.toString(), "sql", null, null, null, null);
        ExecutionResult r = executor.execute(flinkCtx("", ref), l -> {});
        assertThat(r.skipped()).isFalse();
        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.message()).contains("SQL 内容为空");
    }

    @Test
    void execute_fakeFlinkSqlSuccess_passesThroughExit0(@TempDir Path tmp) throws IOException {
        Path home = fakeFlinkHomeIn(tmp);
        EngineSubmitRef ref = new EngineSubmitRef("FLINK", home.toString(), "sql", null, null, null, null);
        StringBuilder out = new StringBuilder();
        ExecutionResult r = executor.execute(flinkCtx("SELECT 1", ref), line -> out.append(line).append('\n'));
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.success()).isTrue();
        assertThat(r.skipped()).isFalse();
        assertThat(r.stdout()).contains("fake-flink-out");
    }

    @Test
    void execute_fakeFlinkSqlFailure_passesThroughNonZero(@TempDir Path tmp) throws IOException {
        Path home = fakeFlinkHomeIn(tmp, "3");
        EngineSubmitRef ref = new EngineSubmitRef("FLINK", home.toString(), "sql", null, null, null, null);
        ExecutionResult r = executor.execute(flinkCtx("SELECT 1", ref), l -> {});
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.success()).isFalse();
        assertThat(r.skipped()).isFalse();
    }

    // ---- execute: jar 形态 ----

    @Test
    void execute_jarNotSpecified_realFailure(@TempDir Path tmp) throws IOException {
        Path home = fakeFlinkHomeIn(tmp);
        EngineSubmitRef ref = new EngineSubmitRef("FLINK", home.toString(), "jar", null, null, null, null);
        ExecutionResult r = executor.execute(flinkCtx("", ref), l -> {});
        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.message()).contains("jar 资产未指定");
    }

    @Test
    void execute_jarMissing_realFailureNotSkipped(@TempDir Path tmp) throws IOException {
        Path home = fakeFlinkHomeIn(tmp);
        EngineSubmitRef ref = new EngineSubmitRef("FLINK", home.toString(), "jar",
                "/nonexistent/app.jar", "com.Main", null, null);
        ExecutionResult r = executor.execute(flinkCtx("", ref), l -> {});
        assertThat(r.success()).isFalse();
        assertThat(r.skipped()).isFalse();
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.message()).contains("jar 资产不存在");
    }

    @Test
    void execute_jarSuccess_passesThroughExit0(@TempDir Path tmp) throws IOException {
        Path home = fakeFlinkHomeIn(tmp);
        Path jar = tmp.resolve("app.jar");
        Files.writeString(jar, "fake-jar");
        EngineSubmitRef ref = new EngineSubmitRef("FLINK", home.toString(), "jar",
                jar.toString(), "com.example.Main", null, null);
        ExecutionResult r = executor.execute(flinkCtx("", ref), l -> {});
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.success()).isTrue();
        assertThat(r.skipped()).isFalse();
    }

    // ---- 超时断言（FR-013）----

    @Test
    void execute_timeout_destroyForciblyAndTimedOut(@TempDir Path tmp) throws IOException {
        Path home = fakeFlinkHomeIn(tmp);
        // 覆盖 bin/sql-client.sh 为 sleep 脚本（timeout=1s → 必超时）
        Path sqlClient = Path.of(home.toString(), "bin", "sql-client.sh");
        Files.writeString(sqlClient, "#!/bin/bash\necho starting...\nsleep 60\necho done\nexit 0\n");
        sqlClient.toFile().setExecutable(true);
        EngineSubmitRef ref = new EngineSubmitRef("FLINK", home.toString(), "sql", null, null, null, null);
        ExecutionResult r = executor.execute(
                new ExecutionContext("SELECT 1", null, 1, 1, "TEST", "FLINK", null, null, null, null, ref),
                l -> {});
        assertThat(r.timedOut()).isTrue();
        assertThat(r.success()).isFalse();
        assertThat(r.skipped()).isFalse();
        assertThat(r.message()).contains("超时");
    }

    // ---- resolveEngineHome ----

    @Test
    void resolveEngineHome_fromRef() {
        EngineSubmitRef ref = new EngineSubmitRef("FLINK", "/custom/flink", null, null, null, null, null);
        assertThat(FlinkTaskExecutor.resolveEngineHome(ref)).isEqualTo("/custom/flink");
    }

    // ---- helpers ----

    private static ExecutionContext flinkCtx(String content, EngineSubmitRef ref) {
        return new ExecutionContext(content, null, 1, 30, "TEST", "FLINK", null, null, null, null, ref);
    }

    /**
     * 造假 ${dir}/bin/flink + bin/sql-client.sh：echo 标记 + 逐 arg 打印（{@code ARG=<value>}）+ exit ${code}。
     */
    private static Path fakeFlinkHomeIn(Path dir, String exitCode) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        String script = "#!/bin/bash\necho fake-flink-out\nfor a in \"$@\"; do echo \"ARG=$a\"; done\nexit "
                + exitCode + "\n";
        Path flink = bin.resolve("flink");
        Files.writeString(flink, script);
        Path sqlClient = bin.resolve("sql-client.sh");
        Files.writeString(sqlClient, script);
        if (!flink.toFile().setExecutable(true) || !sqlClient.toFile().setExecutable(true)) {
            throw new IOException("cannot chmod flink scripts");
        }
        return dir;
    }

    private static Path fakeFlinkHomeIn(Path dir) throws IOException {
        return fakeFlinkHomeIn(dir, "0");
    }
}
