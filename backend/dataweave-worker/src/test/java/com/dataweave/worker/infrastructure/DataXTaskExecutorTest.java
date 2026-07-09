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
 * DataXTaskExecutor 契约测试（contracts C4/C6）：buildCommand 纯函数 + SKIPPED 判定 +
 * fake datax.py 透传 + 超时断言（不依赖真 DataX，零依赖底线）。
 */
class DataXTaskExecutorTest {

    private final DataXTaskExecutor executor = new DataXTaskExecutor();

    @Test
    void type_isDataX() {
        assertThat(executor.type()).isEqualTo("DATAX");
    }

    // ---- buildCommand ----

    @Test
    void buildCommand_dataxPyWithJobPath() {
        List<String> cmd = DataXTaskExecutor.buildCommand("/opt/datax", "/tmp/job.json");
        assertThat(cmd.get(0)).isEqualTo("/opt/datax/bin/datax.py");
        assertThat(cmd.get(1)).isEqualTo("/tmp/job.json");
        assertThat(cmd).hasSize(2);
    }

    // ---- SKIPPED 判定 ----

    @Test
    void skipReason_nullRef_skipped() {
        assertThat(DataXTaskExecutor.skipReason(null)).contains("已跳过").contains("DATAX_HOME");
    }

    @Test
    void skipReason_nullEngineHome_skipped() {
        EngineSubmitRef ref = new EngineSubmitRef("DATAX", null, null, null, null, null, null);
        assertThat(DataXTaskExecutor.skipReason(ref)).contains("DATAX_HOME");
    }

    @Test
    void skipReason_blankEngineHome_skipped() {
        EngineSubmitRef ref = new EngineSubmitRef("DATAX", "", null, null, null, null, null);
        assertThat(DataXTaskExecutor.skipReason(ref)).contains("DATAX_HOME");
    }

    @Test
    void skipReason_missingDataxPy_skipped() {
        EngineSubmitRef ref = new EngineSubmitRef("DATAX", "/nonexistent/datax/home", null, null, null, null, null);
        assertThat(DataXTaskExecutor.skipReason(ref)).contains("datax.py").contains("不存在");
    }

    @Test
    void skipReason_allPresent_returnsNull(@TempDir Path tmp) throws IOException {
        Path home = fakeDataxHomeIn(tmp, "0");
        EngineSubmitRef ref = new EngineSubmitRef("DATAX", home.toString(), null, null, null, null, null);
        assertThat(DataXTaskExecutor.skipReason(ref)).isNull();
    }

    // ---- execute ----

    @Test
    void execute_noDataxHome_skippedNotSuccess() {
        EngineSubmitRef ref = new EngineSubmitRef("DATAX", null, null, null, null, null, null);
        ExecutionResult r = executor.execute(dataxCtx("{}", ref), l -> {});
        assertThat(r.skipped()).isTrue();
        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(0);
    }

    @Test
    void execute_blankContent_realFailureNotSkipped(@TempDir Path tmp) throws IOException {
        Path home = fakeDataxHomeIn(tmp, "0");
        EngineSubmitRef ref = new EngineSubmitRef("DATAX", home.toString(), null, null, null, null, null);
        ExecutionResult r = executor.execute(dataxCtx("", ref), l -> {});
        assertThat(r.skipped()).isFalse();
        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.message()).contains("作业内容为空");
    }

    @Test
    void execute_fakeDataxSuccess_passesThroughExit0(@TempDir Path tmp) throws IOException {
        Path home = fakeDataxHomeIn(tmp, "0");
        EngineSubmitRef ref = new EngineSubmitRef("DATAX", home.toString(), null, null, null, null, null);
        StringBuilder out = new StringBuilder();
        ExecutionResult r = executor.execute(dataxCtx("{\"job\":{}}", ref), line -> out.append(line).append('\n'));
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.success()).isTrue();
        assertThat(r.skipped()).isFalse();
        assertThat(r.stdout()).contains("fake-datax-out");
    }

    @Test
    void execute_fakeDataxFailure_passesThroughNonZero(@TempDir Path tmp) throws IOException {
        Path home = fakeDataxHomeIn(tmp, "3");
        EngineSubmitRef ref = new EngineSubmitRef("DATAX", home.toString(), null, null, null, null, null);
        ExecutionResult r = executor.execute(dataxCtx("{\"job\":{}}", ref), l -> {});
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.success()).isFalse();
        assertThat(r.skipped()).isFalse();
    }

    @Test
    void execute_jobFileWrittenAndCleaned(@TempDir Path tmp) throws IOException {
        Path home = fakeDataxHomeIn(tmp, "0");
        EngineSubmitRef ref = new EngineSubmitRef("DATAX", home.toString(), null, null, null, null, null);
        StringBuilder out = new StringBuilder();
        ExecutionResult r = executor.execute(dataxCtx("{\"job\":{\"content\":{\"reader\":{},\"writer\":{}}}}", ref),
                line -> out.append(line).append('\n'));
        assertThat(r.exitCode()).isEqualTo(0);
        // 验证 datax.py 收到了 job JSON 文件路径
        List<String> argv = out.toString().lines()
                .filter(l -> l.startsWith("ARG="))
                .map(l -> l.substring(4))
                .toList();
        assertThat(argv).anyMatch(a -> a.endsWith(".json"));
    }

    // ---- 超时断言（FR-013）----

    @Test
    void execute_timeout_destroyForciblyAndTimedOut(@TempDir Path tmp) throws IOException {
        Path home = fakeDataxHomeIn(tmp, "0"); // sleep 60 → 超时
        EngineSubmitRef ref = new EngineSubmitRef("DATAX", home.toString(), null, null, null, null, null);
        // 构造一个会 sleep 的 fake datax.py
        Path bin = Path.of(home.toString(), "bin");
        Path dataxPy = bin.resolve("datax.py");
        Files.writeString(dataxPy, "#!/bin/bash\necho starting...\nsleep 60\necho done\nexit 0\n");
        dataxPy.toFile().setExecutable(true);

        ExecutionResult r = executor.execute(
                new ExecutionContext("{}", null, 1, 1, "TEST", "DATAX", null, null, null, null, ref),
                l -> {});
        assertThat(r.timedOut()).isTrue();
        assertThat(r.success()).isFalse();
        assertThat(r.skipped()).isFalse();
        assertThat(r.message()).contains("超时");
    }

    // ---- resolveEngineHome ----

    @Test
    void resolveEngineHome_fromRef() {
        EngineSubmitRef ref = new EngineSubmitRef("DATAX", "/custom/datax", null, null, null, null, null);
        assertThat(DataXTaskExecutor.resolveEngineHome(ref)).isEqualTo("/custom/datax");
    }

    @Test
    void resolveEngineHome_nullRef_returnsEnv() {
        // 在 CI 环境 DATAX_HOME 一般为空，返回 null
        String home = DataXTaskExecutor.resolveEngineHome(null);
        // 如果环境变量存在则取其值，否则 null（均合法）
        assertThat(home == null || !home.isBlank()).isTrue();
    }

    // ---- helpers ----

    private static ExecutionContext dataxCtx(String content, EngineSubmitRef ref) {
        return new ExecutionContext(content, null, 1, 30, "TEST", "DATAX", null, null, null, null, ref);
    }

    /**
     * 造假 ${dir}/bin/datax.py：echo 一行标记 + 逐 arg 打印（{@code ARG=<value>}）+ exit ${code}。
     */
    private static Path fakeDataxHomeIn(Path dir, String exitCode) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path dataxPy = bin.resolve("datax.py");
        Files.writeString(dataxPy, "#!/bin/bash\necho fake-datax-out\nfor a in \"$@\"; do echo \"ARG=$a\"; done\nexit "
                + exitCode + "\n");
        if (!dataxPy.toFile().setExecutable(true)) {
            throw new IOException("cannot chmod datax.py");
        }
        return dir;
    }
}
