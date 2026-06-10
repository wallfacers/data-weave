package com.dataweave.worker.application;

import com.dataweave.worker.application.ControlledCommandExecutor.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 受控执行：白名单外拒绝、白名单内执行、超时终止、输出截断。
 */
class ControlledCommandExecutorTest {

    @Test
    void rejectsCommandNotInWhitelist() {
        ControlledCommandExecutor exec = new ControlledCommandExecutor(List.of("df", "echo"), 60, 65536);
        CommandResult r = exec.execute("rm -rf /data");
        assertThat(r.accepted()).isFalse();
        assertThat(r.exitCode()).isNull();
        assertThat(r.message()).contains("白名单");
    }

    @Test
    void rejectsBlankCommand() {
        ControlledCommandExecutor exec = new ControlledCommandExecutor(List.of("echo"), 60, 65536);
        assertThat(exec.execute("  ").accepted()).isFalse();
    }

    @Test
    void executesWhitelistedCommand() {
        ControlledCommandExecutor exec = new ControlledCommandExecutor(List.of("echo"), 60, 65536);
        CommandResult r = exec.execute("echo hello-dataweave");
        assertThat(r.accepted()).isTrue();
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout()).contains("hello-dataweave");
        assertThat(r.success()).isTrue();
        assertThat(r.timedOut()).isFalse();
    }

    @Test
    void timesOutLongCommand() {
        ControlledCommandExecutor exec = new ControlledCommandExecutor(List.of("sleep"), 1, 65536);
        CommandResult r = exec.execute("sleep 5");
        assertThat(r.timedOut()).isTrue();
        assertThat(r.exitCode()).isNull();
        assertThat(r.message()).contains("超过");
    }

    @Test
    void truncatesLargeOutput() {
        ControlledCommandExecutor exec = new ControlledCommandExecutor(List.of("printf"), 60, 10);
        CommandResult r = exec.execute("printf '%0100d' 0");
        assertThat(r.accepted()).isTrue();
        assertThat(r.truncated()).isTrue();
        assertThat(r.originalBytes()).isGreaterThan(10);
        assertThat(r.stdout().length()).isLessThanOrEqualTo(10);
    }
}
