package com.dataweave.api.application.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dataweave.api.application.supervisor.SupervisorCore.Runtime;
import com.dataweave.api.infrastructure.supervisor.SidecarLauncher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * workhorse sidecar supervisor 真进程集成验证（变更 dataweave-managed-sidecar，tasks 2.4）。
 *
 * <p>用真 workhorse-agent linux 二进制端到端验证 IO 壳 + 控制循环：冷起 spawn→Healthy、adopt 外部健康实例
 * 不重复拉起、kill 子进程触发退避重启、stop() 只 reap 自起进程、外部进程不被误杀。
 *
 * <p>**前置二进制**：默认 {@code deploy/workhorse/bin/workhorse-agent-<goos>-<goarch>}（由 {@code fetch-bin.sh}
 * 产出，gitignore）。缺失则整类 {@code assumeTrue} 跳过——CI/克隆无二进制时不红、不假装通过。
 * 不依赖 Spring 上下文/DB，直接构造 {@link WorkhorseSupervisor}，跑在非默认端口 8399 避免冲突。
 */
class WorkhorseSupervisorRealProcessTest {

    private static final int PORT = 8399;
    private static final String BASE_URL = "http://127.0.0.1:" + PORT;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Path repoRoot;
    private Path binaryDir;
    private String configPath;

    @BeforeEach
    void setUp() {
        repoRoot = findRepoRoot();
        assumeTrue(repoRoot != null, "找不到仓库根（含 deploy/workhorse）——跳过真进程验证");
        binaryDir = repoRoot.resolve("deploy/workhorse/bin");
        configPath = repoRoot.resolve("deploy/workhorse/config.yaml").toString();

        String binName = SupervisorCore.platformBinary(
                System.getProperty("os.name"), System.getProperty("os.arch"));
        Path binary = binaryDir.resolve(binName);
        assumeTrue(SidecarLauncher.binaryRunnable(binary),
                "无 workhorse-agent 二进制 " + binary + "（先跑 deploy/workhorse/fetch-bin.sh）——跳过");

        awaitPortFree(Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        awaitPortFree(Duration.ofSeconds(5));
    }

    /** 冷起：端口空闲 → spawn → /health 收敛 → HEALTHY；stop() reap 自起进程，端口释放。 */
    @Test
    void coldSpawn_convergesHealthy_thenStopReapsOwned() {
        WorkhorseSupervisor sup = supervisor();
        sup.start();
        try {
            awaitState(sup, "HEALTHY", Duration.ofSeconds(25));
            WorkhorseSupervisor.Status st = sup.status();
            assertThat(st.adopted()).isFalse();
            assertThat(st.pid()).as("自起进程应有 PID").isNotNull();
            assertThat(ProcessHandle.of(st.pid())).get().matches(ProcessHandle::isAlive, "子进程存活");

            long ownedPid = st.pid();
            sup.stop();
            awaitProcessDead(ownedPid, Duration.ofSeconds(8));
            assertThat(ProcessHandle.of(ownedPid).map(ProcessHandle::isAlive).orElse(false))
                    .as("stop() 后自起进程已被 reap").isFalse();
        } finally {
            sup.stop();
        }
    }

    /** adopt：端口已有外部健康 sidecar → 标 ADOPTED、不重复 spawn（pid=null）；stop() 不杀外部进程。 */
    @Test
    void adoptExternalHealthy_doesNotSpawn_andNeverReapsExternal() throws Exception {
        Process external = startExternal();
        try {
            awaitHealth(true, Duration.ofSeconds(20));

            WorkhorseSupervisor sup = supervisor();
            sup.start();
            try {
                awaitState(sup, "ADOPTED", Duration.ofSeconds(15));
                WorkhorseSupervisor.Status st = sup.status();
                assertThat(st.adopted()).as("复用外部进程").isTrue();
                assertThat(st.pid()).as("adopt 不持有自起 PID").isNull();

                sup.stop();
                // stop() 只 reap 自起；外部进程必须存活（design D2 不误杀）
                Thread.sleep(1000);
                assertThat(external.isAlive()).as("外部进程不被 reap").isTrue();
            } finally {
                sup.stop();
            }
        } finally {
            external.destroyForcibly();
            external.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** 退避重启：自起 HEALTHY 后外部 kill 子进程 → 控制循环检测 DOWN → 退避重启 → 新 PID 回 HEALTHY。 */
    @Test
    void ownedChildKilled_backoffRestarts_toHealthyWithNewPid() {
        WorkhorseSupervisor sup = supervisor();
        sup.start();
        try {
            awaitState(sup, "HEALTHY", Duration.ofSeconds(25));
            long oldPid = sup.status().pid();

            // 外部 kill 自起子进程，模拟崩溃
            ProcessHandle.of(oldPid).ifPresent(ProcessHandle::destroyForcibly);
            awaitProcessDead(oldPid, Duration.ofSeconds(8));

            // 控制循环应退避重启到新 PID 的 HEALTHY
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            Long newPid = null;
            while (System.nanoTime() < deadline) {
                WorkhorseSupervisor.Status st = sup.status();
                if ("HEALTHY".equals(st.state()) && st.pid() != null && st.pid() != oldPid) {
                    newPid = st.pid();
                    break;
                }
                sleep(300);
            }
            assertThat(newPid).as("退避重启后应有不同的新 PID（当前态=" + sup.status().state() + "）").isNotNull();
            assertThat(newPid).isNotEqualTo(oldPid);
        } finally {
            sup.stop();
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    /** 短探测/快退避的真进程 supervisor（managed=true，native，端口 8399，自动平台选型）。 */
    private WorkhorseSupervisor supervisor() {
        return new WorkhorseSupervisor(
                WebClient.builder(), true, "native", BASE_URL, "/health", PORT, configPath,
                binaryDir.toString(), "", 5,
                500, 20000, 3000, 300, 1500);
    }

    private Process startExternal() throws Exception {
        String binName = SupervisorCore.platformBinary(
                System.getProperty("os.name"), System.getProperty("os.arch"));
        String binPath = binaryDir.resolve(binName).toString();
        return SidecarLauncher.spawn(Runtime.NATIVE, binPath, configPath, "127.0.0.1", PORT, null);
    }

    private void awaitState(WorkhorseSupervisor sup, String target, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (target.equals(sup.status().state())) {
                return;
            }
            sleep(250);
        }
        throw new AssertionError("超时未达状态 " + target + "，当前=" + sup.status().state()
                + " failureReason=" + sup.status().failureReason());
    }

    private void awaitHealth(boolean up, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (health200() == up) {
                return;
            }
            sleep(250);
        }
        throw new AssertionError("超时：/health up=" + up + " 未达成");
    }

    private void awaitProcessDead(long pid, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false) {
                return;
            }
            sleep(200);
        }
    }

    private void awaitPortFree(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && health200()) {
            sleep(250);
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 自 user.dir 上行找含 {@code deploy/workhorse} 的仓库根（测试 cwd 为模块目录）。 */
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
