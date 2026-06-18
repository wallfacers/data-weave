package com.dataweave.api.infrastructure.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dataweave.api.application.supervisor.SupervisorCore;
import com.dataweave.api.application.supervisor.SupervisorCore.Runtime;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * WSL 运行时 IO 壳真机验证（变更 dataweave-managed-sidecar，tasks 4.2/4.3）。
 *
 * <p>WSL interop 下端到端验证：① {@code detectWslDistro()} 真调 {@code wsl.exe -l -q}（UTF-16LE 解码）列发行版；
 * ② {@code spawn(WSL,...)} 经 {@code wsl.exe -d <distro> -- <linux 二进制> serve} 真拉起 sidecar → {@code /health} UP；
 * ③ {@code reap()} 终止 {@code wsl.exe} 父进程 → 端口释放。
 *
 * <p>守门：缺 {@code wsl.exe} interop 或缺二进制 → {@code assumeTrue} 跳过（CI/纯 Linux/Mac 不红）。
 * 注意 {@link SupervisorCore#resolveRuntime} 在非 Windows host 强制 native（design D5），故本测试**直接**测 IO 壳的
 * WSL 分支，不经 supervisor 运行时选型——真正的 Windows-host→WSL 托管由 supervisor 在 Windows 上承载。
 */
class SidecarLauncherWslTest {

    private static final int PORT = 8398;
    private static final String BASE_URL = "http://127.0.0.1:" + PORT;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void detectWslDistro_listsAtLeastOne() {
        Optional<String> distro = SidecarLauncher.detectWslDistro();
        assumeTrue(distro.isPresent(), "无 wsl.exe interop（非 WSL/Windows 环境）——跳过");
        assertThat(distro.get()).isNotBlank();
    }

    @Test
    void wslSpawn_serveHealthy_thenReapFreesPort() throws Exception {
        Optional<String> distro = SidecarLauncher.detectWslDistro();
        assumeTrue(distro.isPresent(), "无 wsl.exe interop——跳过");

        Path repoRoot = findRepoRoot();
        assumeTrue(repoRoot != null, "找不到仓库根——跳过");
        // WSL 命名空间内运行 linux/amd64 二进制
        Path binary = repoRoot.resolve("deploy/workhorse/bin/workhorse-agent-linux-amd64");
        assumeTrue(SidecarLauncher.binaryRunnable(binary),
                "无 workhorse-agent-linux-amd64（先跑 fetch-bin.sh）——跳过");
        String configPath = repoRoot.resolve("deploy/workhorse/config.yaml").toString();

        assumeTrue(!health200(), "端口 8398 已被占用——跳过");

        Process p = SidecarLauncher.spawn(
                Runtime.WSL, binary.toString(), configPath, "127.0.0.1", PORT, distro.get());
        try {
            awaitHealth(true, Duration.ofSeconds(20));
            assertThat(health200()).as("wsl.exe -d %s -- ... serve 起的 sidecar /health UP", distro.get()).isTrue();

            boolean reaped = SidecarLauncher.reap(p, Duration.ofSeconds(5));
            assertThat(reaped).as("reap wsl.exe 父进程返回已退出").isTrue();
            awaitHealth(false, Duration.ofSeconds(8));
            assertThat(health200()).as("reap 后端口释放，/health 不再 UP").isFalse();
        } finally {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private void awaitHealth(boolean up, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (health200() == up) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean health200() {
        try {
            HttpResponse<Void> r = HTTP.send(
                    HttpRequest.newBuilder(URI.create(BASE_URL + "/health"))
                            .timeout(Duration.ofMillis(800)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path findRepoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.isDirectory(p.resolve("deploy/workhorse"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }
}
