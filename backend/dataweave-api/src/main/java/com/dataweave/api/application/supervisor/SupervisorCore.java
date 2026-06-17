package com.dataweave.api.application.supervisor;

/**
 * workhorse sidecar supervisor 的纯函数决策核心（变更 dataweave-managed-sidecar，design D1）。
 *
 * <p>决策核心与 IO 壳（{@code ProcessBuilder} spawn/kill、{@code /health} 探测）解耦：本类无进程、
 * 无网络、无副作用，给定 {@link State} + {@link Snapshot} 推出下一个 {@link Action}/{@link State}，
 * 故可纯单测覆盖 adopt/spawn/restart/reap/互斥/不误杀全分支。
 *
 * <p>不变量：① {@code REAP}/{@code RESTART} 只作用于本 supervisor 自起进程（{@link Ownership#OWNED}），
 * 绝不触及外部进程（{@link Ownership#EXTERNAL}，design D2）；② 端口已被外部健康进程占用 → adopt 复用、
 * 永不 reap；③ 连续重启超过上限进 {@link State#FAILED}，不无限刷。
 */
public final class SupervisorCore {

    /** supervisor 状态机状态。 */
    public enum State { DISABLED, PROBING, ADOPTED, STARTING, HEALTHY, RESTARTING, FAILED }

    /** supervisor 决策动作（由 IO 壳执行）。 */
    public enum Action { NONE, PROBE, ADOPT, SPAWN, RESTART, REAP, FAIL }

    /** {@code /health} 探测结果（UP 健康 / DOWN 无响应或不健康）。 */
    public enum Health { UP, DOWN }

    /** 目标端口占用归属（FREE 无人 / OWNED 本 supervisor 自起 / EXTERNAL 外部进程）。 */
    public enum Ownership { FREE, OWNED, EXTERNAL }

    /** 运行时模式。 */
    public enum Runtime { NATIVE, WSL }

    /**
     * 决策输入快照。
     *
     * @param probe        当前 {@code /health} 探测结果（端口无人占用时视作 {@link Health#DOWN}）
     * @param ownership    端口占用归属（FREE 无人 / OWNED 本 supervisor 自起 / EXTERNAL 外部进程）
     * @param binaryExists 目标平台 sidecar 二进制是否存在且可执行
     * @param restartCount 已连续重启次数（用于退避与上限判定）
     * @param maxRestarts  连续重启上限（超过则进 FAILED）
     */
    public record Snapshot(Health probe, Ownership ownership, boolean binaryExists,
                           int restartCount, int maxRestarts) {
    }

    /** 决策输出：要执行的动作 + 推进到的下一状态。 */
    public record Decision(Action action, State nextState) {
    }

    private SupervisorCore() {
    }

    /** 状态机决策核心：当前状态 + 输入快照 → 动作 + 下一状态。 */
    public static Decision decide(State current, Snapshot s) {
        return switch (current) {
            case DISABLED -> new Decision(Action.NONE, State.DISABLED);
            case FAILED -> new Decision(Action.NONE, State.FAILED);
            case PROBING -> decideProbing(s);
            case STARTING, HEALTHY -> decideRunning(s);
            case ADOPTED -> decideAdopted(s);
            case RESTARTING -> decideRestarting(s);
        };
    }

    /** PROBING：探测端口 → adopt 外部健康实例 / spawn 空闲端口 / 二进制缺失则 Fail。 */
    private static Decision decideProbing(Snapshot s) {
        if (s.ownership() == Ownership.EXTERNAL) {
            // 外部健康 → adopt 复用（绝不 spawn/reap）；外部占用但不健康 → 不抢不杀，继续等待
            return s.probe() == Health.UP
                    ? new Decision(Action.ADOPT, State.ADOPTED)
                    : new Decision(Action.PROBE, State.PROBING);
        }
        if (s.ownership() == Ownership.OWNED && s.probe() == Health.UP) {
            return new Decision(Action.NONE, State.HEALTHY);
        }
        // 端口空闲（或自起进程已不在）→ 拉起；二进制缺失则失败（不静默回退）
        return s.binaryExists()
                ? new Decision(Action.SPAWN, State.STARTING)
                : new Decision(Action.FAIL, State.FAILED);
    }

    /** STARTING / HEALTHY：探测健康保持；自起进程未起来或崩溃 → 退避重启，超上限进 Failed。 */
    private static Decision decideRunning(Snapshot s) {
        if (s.probe() == Health.UP) {
            return new Decision(Action.NONE, State.HEALTHY);
        }
        return s.restartCount() >= s.maxRestarts()
                ? new Decision(Action.FAIL, State.FAILED)
                : new Decision(Action.RESTART, State.RESTARTING);
    }

    /** ADOPTED：外部进程永不 reap；健康则保持，挂了则回 PROBING 重新评估（可能接管 spawn）。 */
    private static Decision decideAdopted(Snapshot s) {
        return s.probe() == Health.UP
                ? new Decision(Action.NONE, State.ADOPTED)
                : new Decision(Action.PROBE, State.PROBING);
    }

    /** RESTARTING：自起进程句柄仍在 → 先 REAP（只杀自己的）；端口已空 → 重新 spawn / 缺二进制则 Fail。 */
    private static Decision decideRestarting(Snapshot s) {
        if (s.ownership() == Ownership.OWNED) {
            return new Decision(Action.REAP, State.RESTARTING);
        }
        return s.binaryExists()
                ? new Decision(Action.SPAWN, State.STARTING)
                : new Decision(Action.FAIL, State.FAILED);
    }

    /** 运行时选型：非 Windows 强制 {@link Runtime#NATIVE}；Windows 且请求 WSL 且 wsl 可用才 WSL（design D5）。 */
    public static Runtime resolveRuntime(String osName, Runtime requested, boolean wslAvailable) {
        boolean windows = osName != null && osName.toLowerCase().contains("win");
        if (windows && requested == Runtime.WSL && wslAvailable) {
            return Runtime.WSL;
        }
        return Runtime.NATIVE;
    }

    /** 指数退避延迟（毫秒）：{@code base * 2^restartCount}，封顶 {@code cap}（逐步左移避免 long 溢出）。 */
    public static long backoffMillis(int restartCount, long baseMillis, long capMillis) {
        long delay = baseMillis;
        for (int i = 0; i < restartCount && delay < capMillis; i++) {
            delay <<= 1;
        }
        return Math.min(delay, capMillis);
    }
}
