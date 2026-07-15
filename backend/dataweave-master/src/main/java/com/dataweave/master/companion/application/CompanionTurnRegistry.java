package com.dataweave.master.companion.application;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.dataweave.master.companion.domain.CompanionStates;
import org.springframework.stereotype.Component;

/**
 * 管家会话活跃轮次登记表（镜像 incident IncidentTurnRegistry，但按项目聚合 think/speak 形态）。
 *
 * <p>{@code CompanionChatService} 在 brain turn 生命周期内：收到指令未回流 → {@link #markThink}（思考）；
 * 开始流式输出 → {@link #markSpeak}（播报）；turn 结束 → {@link #clear}。{@link CompanionStateResolver}
 * 据此判定 think/speak 形态（优先级 speak &gt; think）。
 *
 * <p>项目级聚合：同项目内任一会话有活跃 turn 即驱动管家进入对应形态（spec FR-003）。
 *
 * <p><b>TODO（挂账·多 master 局限）</b>：think/speak 计数是<b>本 master 进程内</b>的内存态——
 * 对等多 master 部署下，A master 的 turn 不会让 B master 感知 think/speak，管家形态在 master 间
 * 可能短暂不一致（最终由 SSE snapshot/state 归一兜底）。要跨 master 一致需把活跃轮次下沉到共享存储
 * （Redis 计数 + TTL），当前单 master / all-in-one 场景下内存态足够，留待 distributed 规模化时再做。
 */
@Component
public class CompanionTurnRegistry {

    private final ConcurrentHashMap<Long, AtomicInteger> thinking = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> speaking = new ConcurrentHashMap<>();

    public void markThink(long projectId) {
        thinking.computeIfAbsent(projectId, k -> new AtomicInteger()).incrementAndGet();
    }

    public void markSpeak(long projectId) {
        speaking.computeIfAbsent(projectId, k -> new AtomicInteger()).incrementAndGet();
    }

    /** turn 结束时同时清 think/speak 计数（一个 turn 可能 think→speak，结束时两者都退）。 */
    public void clear(long projectId) {
        AtomicInteger t = thinking.get(projectId);
        if (t != null && t.get() > 0) t.decrementAndGet();
        AtomicInteger s = speaking.get(projectId);
        if (s != null && s.get() > 0) s.decrementAndGet();
    }

    public boolean isThinking(long projectId) {
        AtomicInteger t = thinking.get(projectId);
        return t != null && t.get() > 0;
    }

    public boolean isSpeaking(long projectId) {
        AtomicInteger s = speaking.get(projectId);
        return s != null && s.get() > 0;
    }

    /** 当前形态（think/speak），无活跃轮次返回 null。 */
    public String phase(long projectId) {
        if (isSpeaking(projectId)) return CompanionStates.SPEAK;
        if (isThinking(projectId)) return CompanionStates.THINK;
        return null;
    }
}
