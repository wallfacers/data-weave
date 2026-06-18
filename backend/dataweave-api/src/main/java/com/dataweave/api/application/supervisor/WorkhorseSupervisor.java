package com.dataweave.api.application.supervisor;

import com.dataweave.api.application.supervisor.SupervisorCore.Decision;
import com.dataweave.api.application.supervisor.SupervisorCore.Health;
import com.dataweave.api.application.supervisor.SupervisorCore.Ownership;
import com.dataweave.api.application.supervisor.SupervisorCore.Runtime;
import com.dataweave.api.application.supervisor.SupervisorCore.Snapshot;
import com.dataweave.api.application.supervisor.SupervisorCore.State;
import com.dataweave.api.infrastructure.supervisor.SidecarHealthProbe;
import com.dataweave.api.infrastructure.supervisor.SidecarLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * workhorse sidecar 生命周期托管（变更 dataweave-managed-sidecar，tasks 3.2/3.3）。
 *
 * <p>把纯函数决策核心 {@link SupervisorCore} 接到 IO 壳（{@link SidecarLauncher} spawn/reap、
 * {@link SidecarHealthProbe} 探测）：作为 {@link SmartLifecycle}，{@code managed=true} 时随后端就绪
 * 在后台控制线程拉起/adopt sidecar，JVM 关闭时 reap 自起进程；{@code managed=false}（默认）完全旁路，
 * 行为与未引入本能力时一致（仅由 {@code WorkhorseHttpClient} 连外部 8300）。
 *
 * <p>不变量（design D2/D3）：① {@code child} 句柄仅在自起时持有，reap 只作用于它；② adopt 的外部进程
 * 永不被终止；③ 同端口单实例由 adopt-or-spawn 自然保证；④ 连续重启超上限进 {@link State#FAILED}。
 */
@Component
public class WorkhorseSupervisor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkhorseSupervisor.class);

    private final boolean managed;
    private final Runtime requestedRuntime;
    private final String baseUrl;
    private final String host;
    private final int port;
    private final String configPath;
    private final String binaryDir;
    private final String binaryOverride;
    private final int maxRestarts;
    private final Duration probeInterval;
    private final Duration startupTimeout;
    private final Duration reapGrace;
    private final long backoffBaseMillis;
    private final long backoffCapMillis;

    private final SidecarHealthProbe healthProbe;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread loopThread;

    // ── 运行态（控制线程独写，其他线程只读快照）──────────────────
    private volatile State state = State.DISABLED;
    private volatile Process child;            // 自起子进程句柄；adopt/未起时为 null
    private volatile boolean adopted;
    private volatile String failureReason;
    private volatile int restartCount;
    private volatile Runtime effectiveRuntime;
    private volatile String wslDistro;

    public WorkhorseSupervisor(
            WebClient.Builder webClientBuilder,
            @Value("${agent.workhorse.managed:false}") boolean managed,
            @Value("${agent.workhorse.runtime:native}") String runtime,
            @Value("${agent.workhorse.base-url:http://127.0.0.1:8300}") String baseUrl,
            @Value("${agent.workhorse.health-path:/health}") String healthPath,
            @Value("${agent.workhorse.port:8300}") int port,
            @Value("${agent.workhorse.config-path:deploy/workhorse/config.yaml}") String configPath,
            @Value("${agent.workhorse.binary-dir:deploy/workhorse/bin}") String binaryDir,
            @Value("${agent.workhorse.binary:}") String binaryOverride,
            @Value("${agent.workhorse.max-restarts:5}") int maxRestarts,
            @Value("${agent.workhorse.probe-interval-ms:3000}") long probeIntervalMs,
            @Value("${agent.workhorse.startup-timeout-ms:30000}") long startupTimeoutMs,
            @Value("${agent.workhorse.reap-grace-ms:5000}") long reapGraceMs,
            @Value("${agent.workhorse.backoff-base-ms:1000}") long backoffBaseMillis,
            @Value("${agent.workhorse.backoff-cap-ms:30000}") long backoffCapMillis) {
        this.managed = managed;
        this.requestedRuntime = "wsl".equalsIgnoreCase(runtime) ? Runtime.WSL : Runtime.NATIVE;
        this.baseUrl = baseUrl;
        this.host = hostOf(baseUrl);
        this.port = port;
        this.configPath = configPath;
        this.binaryDir = binaryDir;
        this.binaryOverride = binaryOverride;
        this.maxRestarts = maxRestarts;
        this.probeInterval = Duration.ofMillis(probeIntervalMs);
        this.startupTimeout = Duration.ofMillis(startupTimeoutMs);
        this.reapGrace = Duration.ofMillis(reapGraceMs);
        this.backoffBaseMillis = backoffBaseMillis;
        this.backoffCapMillis = backoffCapMillis;
        this.healthProbe = new SidecarHealthProbe(webClientBuilder, baseUrl, healthPath, this.probeInterval);
    }

    // ── SmartLifecycle ────────────────────────────────────────────

    @Override
    public void start() {
        if (!managed) {
            log.info("workhorse supervisor: managed=false，旁路（外部托管 {}）", baseUrl);
            state = State.DISABLED;
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        this.effectiveRuntime = SupervisorCore.resolveRuntime(
                System.getProperty("os.name"), requestedRuntime, wslAvailable());
        state = State.PROBING;
        Thread t = new Thread(this::controlLoop, "workhorse-supervisor");
        t.setDaemon(true);
        this.loopThread = t;
        t.start();
        log.info("workhorse supervisor: managed=true 启动（runtime={}, port={}, binary={}）",
                effectiveRuntime, port, binaryPath());
    }

    @Override
    public void stop() {
        running.set(false);
        Thread t = loopThread;
        if (t != null) {
            t.interrupt();
        }
        reapOwned();   // JVM 退出只回收自己拉起的；adopt 的外部进程不动（design D2）
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** 晚启动、早停止：在 Web 端点可用后拉起，关闭最先 reap。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    // ── 控制循环（单后台线程独占运行态写）────────────────────────

    private void controlLoop() {
        while (running.get() && state != State.FAILED && state != State.DISABLED) {
            try {
                step();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("workhorse supervisor 循环异常：{}", e.toString());
                sleepQuietly(probeInterval);
            }
        }
    }

    private void step() throws InterruptedException {
        switch (state) {
            case PROBING -> stepProbing();
            case STARTING -> stepStarting();
            case HEALTHY -> stepHealthy();
            case RESTARTING -> stepRestarting();
            case ADOPTED -> stepAdopted();
            default -> { /* DISABLED/FAILED 由循环条件退出 */ }
        }
    }

    /** PROBING：探端口 → adopt 外部健康 / spawn 空闲 / 二进制缺失 Fail。 */
    private void stepProbing() throws InterruptedException {
        Health probe = healthProbe.probe();
        Ownership ownership = ownership(probe);
        Decision d = SupervisorCore.decide(State.PROBING, snapshot(probe, ownership));
        switch (d.action()) {
            case ADOPT -> {
                adopted = true;
                child = null;
                log.info("workhorse supervisor: adopt 外部健康 sidecar @ {}（不接管其生命周期）", baseUrl);
            }
            case SPAWN -> spawnChild();
            case FAIL -> fail("二进制缺失或不可执行：" + binaryPath() + "（参见 workhorse-agent scripts/build.sh）");
            default -> { /* PROBE/NONE：继续等待 */ }
        }
        state = d.nextState();
        if (state == State.PROBING) {
            sleepQuietly(probeInterval);
        }
    }

    /** STARTING：自起后做就绪等待（design D7），收敛 → HEALTHY；超时未起 → 交核心判退避/Fail。 */
    private void stepStarting() throws InterruptedException {
        if (waitForHealthy()) {
            restartCount = 0;
            state = State.HEALTHY;
            log.info("workhorse supervisor: sidecar 就绪 Healthy @ {}", baseUrl);
            return;
        }
        Decision d = SupervisorCore.decide(State.STARTING, snapshot(Health.DOWN, ownership(Health.DOWN)));
        if (d.action() == SupervisorCore.Action.FAIL) {
            fail("启动后 " + startupTimeout.toMillis() + "ms 内未收敛健康，重启达上限");
        }
        state = d.nextState();
    }

    /** HEALTHY：周期探测，崩溃（DOWN）→ 交核心判退避重启 / Fail。 */
    private void stepHealthy() throws InterruptedException {
        sleepQuietly(probeInterval);
        Health probe = healthProbe.probe();
        if (probe == Health.UP) {
            return;
        }
        log.warn("workhorse supervisor: sidecar 失联（health DOWN），评估重启");
        Decision d = SupervisorCore.decide(State.HEALTHY, snapshot(Health.DOWN, Ownership.OWNED));
        if (d.action() == SupervisorCore.Action.FAIL) {
            fail("运行中崩溃且重启达上限");
        }
        state = d.nextState();
    }

    /** RESTARTING：先 reap 自起进程（只杀自己的），退避后重新 spawn；二进制缺失 Fail。 */
    private void stepRestarting() throws InterruptedException {
        reapOwned();
        restartCount++;
        long backoff = SupervisorCore.backoffMillis(restartCount, backoffBaseMillis, backoffCapMillis);
        log.info("workhorse supervisor: 第 {} 次退避重启，等待 {}ms", restartCount, backoff);
        sleepQuietly(Duration.ofMillis(backoff));
        if (!binaryRunnable()) {
            fail("重启时二进制缺失：" + binaryPath());
            return;
        }
        spawnChild();
        state = State.STARTING;
    }

    /** ADOPTED：外部进程永不 reap；健康保持，挂了回 PROBING（可能接管 spawn）。 */
    private void stepAdopted() throws InterruptedException {
        sleepQuietly(probeInterval);
        Health probe = healthProbe.probe();
        Decision d = SupervisorCore.decide(State.ADOPTED, snapshot(probe, Ownership.EXTERNAL));
        if (d.nextState() == State.PROBING) {
            adopted = false;
            log.info("workhorse supervisor: 被 adopt 的外部 sidecar 失联，回 PROBING 重新评估");
        }
        state = d.nextState();
    }

    // ── IO 动作 ───────────────────────────────────────────────────

    private void spawnChild() {
        try {
            SidecarLauncher.ensureDir(binaryDir);
            Process p = SidecarLauncher.spawn(effectiveRuntime, binaryPath(), configPath, host, port,
                    wslDistro);
            this.child = p;
            this.adopted = false;
            log.info("workhorse supervisor: spawn sidecar pid={} runtime={}",
                    p.pid(), effectiveRuntime);
        } catch (Exception e) {
            fail("spawn 失败：" + e.getMessage());
        }
    }

    private void reapOwned() {
        Process p = this.child;
        if (p == null) {
            return;
        }
        boolean dead = SidecarLauncher.reap(p, reapGrace);
        log.info("workhorse supervisor: reap 自起 sidecar pid={} dead={}", p.pid(), dead);
        this.child = null;
    }

    /** 就绪等待：自 spawn 起按 probeInterval 轮询 /health 直到 UP 或超 startupTimeout（design D7）。 */
    private boolean waitForHealthy() throws InterruptedException {
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        while (running.get() && System.nanoTime() < deadline) {
            // 子进程已退出则无需再等（提前判失败）
            Process p = this.child;
            if (p != null && !p.isAlive()) {
                return false;
            }
            if (healthProbe.probe() == Health.UP) {
                return true;
            }
            Thread.sleep(probeInterval.toMillis());
        }
        return false;
    }

    // ── 快照 / 选型辅助 ───────────────────────────────────────────

    private Snapshot snapshot(Health probe, Ownership ownership) {
        return new Snapshot(probe, ownership, binaryRunnable(), restartCount, maxRestarts);
    }

    /** 归属判定：持有存活子进程 → OWNED；否则端口健康 → EXTERNAL；都不是 → FREE。 */
    private Ownership ownership(Health probe) {
        Process p = this.child;
        if (p != null && p.isAlive()) {
            return Ownership.OWNED;
        }
        return probe == Health.UP ? Ownership.EXTERNAL : Ownership.FREE;
    }

    private void fail(String reason) {
        this.failureReason = reason;
        this.state = State.FAILED;
        log.error("workhorse supervisor 进入 FAILED：{}", reason);
    }

    private boolean wslAvailable() {
        if (requestedRuntime != Runtime.WSL) {
            return false;
        }
        Optional<String> distro = SidecarLauncher.detectWslDistro();
        distro.ifPresent(d -> this.wslDistro = d);
        return distro.isPresent();
    }

    private boolean binaryRunnable() {
        return SidecarLauncher.binaryRunnable(binaryPathResolved());
    }

    private Path binaryPathResolved() {
        return SidecarLauncher.resolveBinary(binaryDir, binaryName());
    }

    private String binaryPath() {
        return binaryPathResolved().toString();
    }

    private String binaryName() {
        if (binaryOverride != null && !binaryOverride.isBlank()) {
            return binaryOverride;
        }
        return SupervisorCore.platformBinary(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    private static String hostOf(String baseUrl) {
        try {
            return java.net.URI.create(baseUrl).getHost();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 可观测快照（tasks 3.3，供 /api/ops）───────────────────────

    /**
     * supervisor 可观测快照：状态机当前态、adopt 标记、自起 PID、失败原因、运行时（design 3.3）。
     * {@code managed=false} 时标识「外部托管」。
     */
    public Status status() {
        Process p = this.child;
        Long pid = (p != null && p.isAlive()) ? p.pid() : null;
        return new Status(managed, state.name(), adopted, pid,
                effectiveRuntime == null ? requestedRuntime.name() : effectiveRuntime.name(),
                baseUrl, failureReason, managed ? null : "external");
    }

    /**
     * supervisor 健康快照。
     *
     * @param managed       是否托管模式
     * @param state         状态机当前态（DISABLED/PROBING/ADOPTED/STARTING/HEALTHY/RESTARTING/FAILED）
     * @param adopted       当前 sidecar 是否为 adopt 的外部进程
     * @param pid           自起子进程 PID（adopt/未起时为 null）
     * @param runtime       生效运行时（NATIVE/WSL）
     * @param baseUrl       sidecar 基址
     * @param failureReason 失败原因（FAILED 时有值）
     * @param custody       托管归属标记：managed=false 时为 {@code "external"}，否则 null
     */
    public record Status(boolean managed, String state, boolean adopted, Long pid,
                         String runtime, String baseUrl, String failureReason, String custody) {
    }
}
