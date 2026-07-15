package com.dataweave.master.companion.domain;

/**
 * 管家形态（服务端归一，SSE {@code state} 事件推送）。前端只渲染不推断。
 *
 * <p>优先级（高→低）：{@code speak} &gt; {@code think} &gt; {@code alert} &gt; {@code patrol} &gt; {@code idle}
 * （data-model 派生状态节）。
 */
public final class CompanionStates {

    public static final String IDLE = "idle";       // 其余（待机/微笑）
    public static final String PATROL = "patrol";   // 存在 RUNNING 的 patrol_run（巡检中）
    public static final String ALERT = "alert";     // 存在未关闭异常汇报 DANGER/WARN（警觉）
    public static final String THINK = "think";     // 当前会话有进行中 brain turn（思考）
    public static final String SPEAK = "speak";     // brain turn 正在流式输出（播报）

    private CompanionStates() {}
}
