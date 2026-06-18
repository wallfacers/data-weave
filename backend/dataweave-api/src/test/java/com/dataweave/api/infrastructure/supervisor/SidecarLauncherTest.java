package com.dataweave.api.infrastructure.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.dataweave.api.application.supervisor.SupervisorCore.Runtime;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * sidecar IO 壳命令构造与二进制解析单测（变更 dataweave-managed-sidecar，tasks 2.1/4.2/4.3）。
 *
 * <p>spawn/reap 的真进程行为属本机真机验证（tasks 2.4，待 workhorse-agent 二进制就位）；
 * 此处覆盖与平台/进程无关的纯逻辑：命令形状（native/wsl）、binary-dir 解析、绝对路径直通。
 */
class SidecarLauncherTest {

    @Test
    void buildCommand_native_serveWithConfigHostPort() {
        List<String> cmd = SidecarLauncher.buildCommand(
                Runtime.NATIVE, "/opt/wh/workhorse-agent-linux-amd64",
                "deploy/workhorse/config.yaml", "127.0.0.1", 8300, null);
        assertThat(cmd).containsExactly(
                "/opt/wh/workhorse-agent-linux-amd64", "serve",
                "--config", "deploy/workhorse/config.yaml",
                "--host", "127.0.0.1", "--port", "8300");
    }

    @Test
    void buildCommand_wsl_prefixesWslDashD() {
        List<String> cmd = SidecarLauncher.buildCommand(
                Runtime.WSL, "/opt/wh/workhorse-agent-linux-amd64",
                "/etc/workhorse/config.yaml", "127.0.0.1", 8300, "Ubuntu");
        assertThat(cmd).containsExactly(
                "wsl.exe", "-d", "Ubuntu", "--",
                "/opt/wh/workhorse-agent-linux-amd64", "serve",
                "--config", "/etc/workhorse/config.yaml",
                "--host", "127.0.0.1", "--port", "8300");
    }

    @Test
    void resolveBinary_joinsDirAndName() {
        assertThat(SidecarLauncher.resolveBinary("deploy/workhorse/bin", "workhorse-agent-linux-amd64"))
                .isEqualTo(Path.of("deploy/workhorse/bin/workhorse-agent-linux-amd64"));
    }

    @Test
    void resolveBinary_absoluteNameOverridesDir() {
        assertThat(SidecarLauncher.resolveBinary("deploy/workhorse/bin", "/usr/local/bin/workhorse-agent"))
                .isEqualTo(Path.of("/usr/local/bin/workhorse-agent"));
    }

    @Test
    void binaryRunnable_missingFile_false() {
        assertThat(SidecarLauncher.binaryRunnable(Path.of("/no/such/workhorse-agent-binary"))).isFalse();
    }

    @Test
    void reap_nullOrDeadProcess_treatedAsReaped() {
        assertThat(SidecarLauncher.reap(null, java.time.Duration.ofMillis(10))).isTrue();
    }
}
