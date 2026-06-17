package com.dataweave.api.application.supervisor;

import static com.dataweave.api.application.supervisor.SupervisorCore.Action.*;
import static com.dataweave.api.application.supervisor.SupervisorCore.Health.*;
import static com.dataweave.api.application.supervisor.SupervisorCore.Ownership.*;
import static com.dataweave.api.application.supervisor.SupervisorCore.Runtime.*;
import static com.dataweave.api.application.supervisor.SupervisorCore.State.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.dataweave.api.application.supervisor.SupervisorCore.Decision;
import com.dataweave.api.application.supervisor.SupervisorCore.Snapshot;
import org.junit.jupiter.api.Test;

/**
 * supervisor 决策核心全分支单测（变更 dataweave-managed-sidecar，tasks 1.3）：
 * adopt 不 spawn、自起崩溃退避重启、连续失败进 Failed、reap 只杀自起、Adopted 不 reap、
 * binary 缺失 Fail、运行时选型、指数退避。
 */
class SupervisorCoreTest {

    private static Snapshot snap(SupervisorCore.Health probe, SupervisorCore.Ownership own,
                                 boolean binary, int count, int max) {
        return new Snapshot(probe, own, binary, count, max);
    }

    // ── PROBING：探测后 adopt / spawn / fail ──────────────────────

    @Test
    void probing_externalHealthy_adoptsAndDoesNotSpawn() {
        Decision d = SupervisorCore.decide(PROBING, snap(UP, EXTERNAL, true, 0, 3));
        assertThat(d).isEqualTo(new Decision(ADOPT, ADOPTED));
    }

    @Test
    void probing_noOccupantWithBinary_spawns() {
        Decision d = SupervisorCore.decide(PROBING, snap(DOWN, FREE, true, 0, 3));
        assertThat(d).isEqualTo(new Decision(SPAWN, STARTING));
    }

    @Test
    void probing_noOccupantBinaryMissing_fails() {
        Decision d = SupervisorCore.decide(PROBING, snap(DOWN, FREE, false, 0, 3));
        assertThat(d).isEqualTo(new Decision(FAIL, FAILED));
    }

    @Test
    void probing_externalUnhealthy_keepsProbingNeverReap() {
        // 端口被外部进程占用但不健康：不抢不杀（D2），继续探测等待
        Decision d = SupervisorCore.decide(PROBING, snap(DOWN, EXTERNAL, true, 0, 3));
        assertThat(d).isEqualTo(new Decision(PROBE, PROBING));
        assertThat(d.action()).isNotEqualTo(REAP);
    }

    // ── STARTING：健康收敛 / 超时退避 ─────────────────────────────

    @Test
    void starting_healthy_becomesHealthy() {
        Decision d = SupervisorCore.decide(STARTING, snap(UP, OWNED, true, 0, 3));
        assertThat(d).isEqualTo(new Decision(NONE, HEALTHY));
    }

    @Test
    void starting_timeoutUnderLimit_restarts() {
        Decision d = SupervisorCore.decide(STARTING, snap(DOWN, OWNED, true, 0, 3));
        assertThat(d).isEqualTo(new Decision(RESTART, RESTARTING));
    }

    @Test
    void starting_timeoutAtLimit_fails() {
        Decision d = SupervisorCore.decide(STARTING, snap(DOWN, OWNED, true, 3, 3));
        assertThat(d).isEqualTo(new Decision(FAIL, FAILED));
    }

    // ── HEALTHY：稳态 / 崩溃退避重启 ──────────────────────────────

    @Test
    void healthy_stillHealthy_staysHealthy() {
        Decision d = SupervisorCore.decide(HEALTHY, snap(UP, OWNED, true, 0, 3));
        assertThat(d).isEqualTo(new Decision(NONE, HEALTHY));
    }

    @Test
    void healthy_ownedCrashUnderLimit_restarts() {
        Decision d = SupervisorCore.decide(HEALTHY, snap(DOWN, OWNED, true, 0, 3));
        assertThat(d).isEqualTo(new Decision(RESTART, RESTARTING));
    }

    @Test
    void healthy_ownedCrashAtLimit_fails() {
        Decision d = SupervisorCore.decide(HEALTHY, snap(DOWN, OWNED, true, 3, 3));
        assertThat(d).isEqualTo(new Decision(FAIL, FAILED));
    }

    // ── RESTARTING：退避结束后重新 spawn（reap 已在进入前完成）────

    @Test
    void restarting_portFreedWithBinary_spawns() {
        Decision d = SupervisorCore.decide(RESTARTING, snap(DOWN, FREE, true, 1, 3));
        assertThat(d).isEqualTo(new Decision(SPAWN, STARTING));
    }

    @Test
    void restarting_stillOwned_reapsFirst() {
        // 进入 RESTARTING 但自起进程句柄仍在 → 先 REAP 自己的进程，再下一拍 spawn
        Decision d = SupervisorCore.decide(RESTARTING, snap(DOWN, OWNED, true, 1, 3));
        assertThat(d).isEqualTo(new Decision(REAP, RESTARTING));
    }

    // ── ADOPTED：外部健康进程，永不 reap ─────────────────────────

    @Test
    void adopted_externalHealthy_staysAdoptedNeverReap() {
        Decision d = SupervisorCore.decide(ADOPTED, snap(UP, EXTERNAL, true, 0, 3));
        assertThat(d).isEqualTo(new Decision(NONE, ADOPTED));
        assertThat(d.action()).isNotEqualTo(REAP);
    }

    @Test
    void adopted_externalDied_reprobesNeverReap() {
        Decision d = SupervisorCore.decide(ADOPTED, snap(DOWN, EXTERNAL, true, 0, 3));
        assertThat(d).isEqualTo(new Decision(PROBE, PROBING));
        assertThat(d.action()).isNotEqualTo(REAP);
    }

    // ── 终态 ──────────────────────────────────────────────────────

    @Test
    void failed_isTerminal() {
        Decision d = SupervisorCore.decide(FAILED, snap(DOWN, FREE, true, 3, 3));
        assertThat(d).isEqualTo(new Decision(NONE, FAILED));
    }

    @Test
    void disabled_staysIdle() {
        Decision d = SupervisorCore.decide(DISABLED, snap(DOWN, FREE, true, 0, 3));
        assertThat(d).isEqualTo(new Decision(NONE, DISABLED));
    }

    // ── 运行时选型（design D5）────────────────────────────────────

    @Test
    void resolveRuntime_nonWindows_forcesNative() {
        assertThat(SupervisorCore.resolveRuntime("Linux", WSL, true)).isEqualTo(NATIVE);
        assertThat(SupervisorCore.resolveRuntime("Mac OS X", WSL, true)).isEqualTo(NATIVE);
    }

    @Test
    void resolveRuntime_windowsWslRequestedAndAvailable_wsl() {
        assertThat(SupervisorCore.resolveRuntime("Windows 11", WSL, true)).isEqualTo(WSL);
    }

    @Test
    void resolveRuntime_windowsWslRequestedButUnavailable_fallsBackNative() {
        assertThat(SupervisorCore.resolveRuntime("Windows 11", WSL, false)).isEqualTo(NATIVE);
    }

    @Test
    void resolveRuntime_nativeRequested_native() {
        assertThat(SupervisorCore.resolveRuntime("Windows 11", NATIVE, true)).isEqualTo(NATIVE);
    }

    // ── 指数退避 ──────────────────────────────────────────────────

    @Test
    void backoff_isExponential() {
        assertThat(SupervisorCore.backoffMillis(0, 1000, 30000)).isEqualTo(1000);
        assertThat(SupervisorCore.backoffMillis(1, 1000, 30000)).isEqualTo(2000);
        assertThat(SupervisorCore.backoffMillis(2, 1000, 30000)).isEqualTo(4000);
        assertThat(SupervisorCore.backoffMillis(3, 1000, 30000)).isEqualTo(8000);
    }

    @Test
    void backoff_cappedAtCeiling() {
        assertThat(SupervisorCore.backoffMillis(20, 1000, 30000)).isEqualTo(30000);
    }
}
