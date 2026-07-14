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
 * SeaTunnelTaskExecutor 契约测试（contracts C4/C6）：buildCommand 纯函数 + SKIPPED 判定 +
 * fake seatunnel.sh 透传 + 超时断言（不依赖真 SeaTunnel，零依赖底线）。
 */
class SeaTunnelTaskExecutorTest {

    private final SeaTunnelTaskExecutor executor = new SeaTunnelTaskExecutor();

    @Test
    void type_isSeaTunnel() {
        assertThat(executor.type()).isEqualTo("SEATUNNEL");
    }

    // ---- buildCommand ----

    @Test
    void buildCommand_seatunnelShWithConfig() {
        List<String> cmd = SeaTunnelTaskExecutor.buildCommand("/opt/seatunnel", "/tmp/config.conf");
        assertThat(cmd.get(0)).isEqualTo("/opt/seatunnel/bin/seatunnel.sh");
        assertThat(cmd.get(1)).isEqualTo("--master");
        assertThat(cmd.get(2)).isEqualTo("local");
        assertThat(cmd.get(3)).isEqualTo("--config");
        assertThat(cmd.get(4)).isEqualTo("/tmp/config.conf");
        assertThat(cmd).hasSize(5);
    }

    // ---- SKIPPED 判定 ----

    @Test
    void skipReason_nullRef_skipped() {
        assertThat(SeaTunnelTaskExecutor.skipReason(null)).contains("已跳过").contains("SEATUNNEL_HOME");
    }

    @Test
    void skipReason_nullEngineHome_skipped() {
        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", null, null, null, null, null, null);
        assertThat(SeaTunnelTaskExecutor.skipReason(ref)).contains("SEATUNNEL_HOME");
    }

    @Test
    void skipReason_blankEngineHome_skipped() {
        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", "", null, null, null, null, null);
        assertThat(SeaTunnelTaskExecutor.skipReason(ref)).contains("SEATUNNEL_HOME");
    }

    @Test
    void skipReason_missingSeatunnelSh_skipped() {
        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", "/nonexistent/seatunnel/home", null, null, null, null, null);
        assertThat(SeaTunnelTaskExecutor.skipReason(ref)).contains("seatunnel.sh").contains("不存在");
    }

    @Test
    void skipReason_allPresent_returnsNull(@TempDir Path tmp) throws IOException {
        Path home = fakeSeatunnelHomeIn(tmp, "0");
        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", home.toString(), null, null, null, null, null);
        assertThat(SeaTunnelTaskExecutor.skipReason(ref)).isNull();
    }

    // ---- execute ----

    @Test
    void execute_noSeatunnelHome_skippedNotSuccess() {
        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", null, null, null, null, null, null);
        ExecutionResult r = executor.execute(seatunnelCtx("env {}", ref), l -> {});
        assertThat(r.skipped()).isTrue();
        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(0);
    }

    @Test
    void execute_blankContent_realFailureNotSkipped(@TempDir Path tmp) throws IOException {
        Path home = fakeSeatunnelHomeIn(tmp, "0");
        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", home.toString(), null, null, null, null, null);
        ExecutionResult r = executor.execute(seatunnelCtx("", ref), l -> {});
        assertThat(r.skipped()).isFalse();
        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.message()).contains("配置内容为空");
    }

    @Test
    void execute_fakeSeatunnelSuccess_passesThroughExit0(@TempDir Path tmp) throws IOException {
        Path home = fakeSeatunnelHomeIn(tmp, "0");
        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", home.toString(), null, null, null, null, null);
        StringBuilder out = new StringBuilder();
        ExecutionResult r = executor.execute(
                seatunnelCtx("env { parallelism = 1 }", ref), line -> out.append(line).append('\n'));
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.success()).isTrue();
        assertThat(r.skipped()).isFalse();
        assertThat(r.stdout()).contains("fake-seatunnel-out");
    }

    @Test
    void execute_fakeSeatunnelFailure_passesThroughNonZero(@TempDir Path tmp) throws IOException {
        Path home = fakeSeatunnelHomeIn(tmp, "5");
        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", home.toString(), null, null, null, null, null);
        ExecutionResult r = executor.execute(seatunnelCtx("env {}", ref), l -> {});
        assertThat(r.exitCode()).isEqualTo(5);
        assertThat(r.success()).isFalse();
        assertThat(r.skipped()).isFalse();
    }

    @Test
    void execute_configFileWrittenWithContent(@TempDir Path tmp) throws IOException {
        Path home = fakeSeatunnelHomeIn(tmp, "0");
        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", home.toString(), null, null, null, null, null);
        String configContent = "env {\n  job.mode = \"BATCH\"\n}";
        StringBuilder out = new StringBuilder();
        ExecutionResult r = executor.execute(seatunnelCtx(configContent, ref),
                line -> out.append(line).append('\n'));
        assertThat(r.exitCode()).isEqualTo(0);
        // 验证 seatunnel.sh 收到了配置文件路径（--config 后的 arg）
        List<String> argv = out.toString().lines()
                .filter(l -> l.startsWith("ARG="))
                .map(l -> l.substring(4))
                .toList();
        assertThat(argv).contains("--config");
        int configIdx = argv.indexOf("--config");
        assertThat(configIdx).isNotNegative();
        assertThat(argv.get(configIdx + 1)).endsWith(".conf");
    }

    // ---- 067 T018: 资源提示（JVM_ARGS 环境变量，seatunnel.sh 无 CLI flag）----

    @Test
    void execute_withMemoryHint_setsJvmArgsEnvVar(@TempDir Path tmp) throws IOException {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path seatunnelSh = bin.resolve("seatunnel.sh");
        Files.writeString(seatunnelSh, "#!/bin/bash\necho \"JVM_ARGS=$JVM_ARGS\"\nexit 0\n");
        seatunnelSh.toFile().setExecutable(true);
        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", tmp.toString(), null, null, null, null, null,
                false, null, null, 4096, null);
        StringBuilder out = new StringBuilder();
        ExecutionResult r = executor.execute(seatunnelCtx("env {}", ref), line -> out.append(line).append('\n'));
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(out.toString()).contains("JVM_ARGS=-Xms4096m -Xmx4096m");
    }

    @Test
    void execute_noMemoryHint_leavesJvmArgsUnset(@TempDir Path tmp) throws IOException {
        Path home = fakeSeatunnelHomeIn(tmp, "0");
        Path seatunnelSh = Path.of(home.toString(), "bin", "seatunnel.sh");
        Files.writeString(seatunnelSh, "#!/bin/bash\necho \"JVM_ARGS=[$JVM_ARGS]\"\nexit 0\n");
        seatunnelSh.toFile().setExecutable(true);
        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", home.toString(), null, null, null, null, null);
        StringBuilder out = new StringBuilder();
        ExecutionResult r = executor.execute(seatunnelCtx("env {}", ref), line -> out.append(line).append('\n'));
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(out.toString()).contains("JVM_ARGS=[]");
    }

    // ---- 超时断言（FR-013）----

    @Test
    void execute_timeout_destroyForciblyAndTimedOut(@TempDir Path tmp) throws IOException {
        Path home = fakeSeatunnelHomeIn(tmp, "0");
        // 覆盖为会 sleep 的 fake seatunnel.sh
        Path bin = Path.of(home.toString(), "bin");
        Path seatunnelSh = bin.resolve("seatunnel.sh");
        Files.writeString(seatunnelSh, "#!/bin/bash\necho starting...\nsleep 60\necho done\nexit 0\n");
        seatunnelSh.toFile().setExecutable(true);

        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", home.toString(), null, null, null, null, null);
        ExecutionResult r = executor.execute(
                new ExecutionContext("env {}", null, 1, 1, "TEST", "SEATUNNEL", null, null, null, null, ref),
                l -> {});
        assertThat(r.timedOut()).isTrue();
        assertThat(r.success()).isFalse();
        assertThat(r.skipped()).isFalse();
        assertThat(r.message()).contains("超时");
    }

    // ---- resolveEngineHome ----

    @Test
    void resolveEngineHome_fromRef() {
        EngineSubmitRef ref = new EngineSubmitRef("SEATUNNEL", "/custom/seatunnel", null, null, null, null, null);
        assertThat(SeaTunnelTaskExecutor.resolveEngineHome(ref)).isEqualTo("/custom/seatunnel");
    }

    @Test
    void resolveEngineHome_nullRef_returnsEnv() {
        String home = SeaTunnelTaskExecutor.resolveEngineHome(null);
        assertThat(home == null || !home.isBlank()).isTrue();
    }

    // ---- helpers ----

    private static ExecutionContext seatunnelCtx(String content, EngineSubmitRef ref) {
        return new ExecutionContext(content, null, 1, 30, "TEST", "SEATUNNEL", null, null, null, null, ref);
    }

    /**
     * 造假 ${dir}/bin/seatunnel.sh：echo 一行标记 + 逐 arg 打印（{@code ARG=<value>}）+ exit ${code}。
     */
    private static Path fakeSeatunnelHomeIn(Path dir, String exitCode) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path seatunnelSh = bin.resolve("seatunnel.sh");
        Files.writeString(seatunnelSh, "#!/bin/bash\necho fake-seatunnel-out\nfor a in \"$@\"; do echo \"ARG=$a\"; done\nexit "
                + exitCode + "\n");
        if (!seatunnelSh.toFile().setExecutable(true)) {
            throw new IOException("cannot chmod seatunnel.sh");
        }
        return dir;
    }
}
