package com.dataweave.master.companion.infrastructure;

import com.dataweave.master.companion.domain.ChatCallbacks;
import com.dataweave.master.companion.domain.ChatHandle;
import com.dataweave.master.companion.domain.CompanionBrain;
import com.dataweave.master.companion.domain.PatrolResult;
import com.dataweave.master.companion.domain.PatrolRoutine;
import org.springframework.stereotype.Component;

/**
 * 大脑降级适配器（测试 / workhorse 未就绪兜底）。
 *
 * <ul>
 *   <li>{@link #runPatrol} 永远 {@link PatrolResult#failed}——交由 {@code PatrolService} 兜底产 INFO"未完成"汇报
 *       （workhorse 不可用时巡检不静默丢失，SC-007）。</li>
 *   <li>{@link #openChat} 返回吐固定降级话术的句柄（仅供测试/直连；生产对话路径在 workhorse 不可用时
 *       由 {@code CompanionBrainSelector#forChat} 返回 empty，{@code CompanionChatService} 抛
 *       {@code companion.brain_unavailable}，不走 mock）。</li>
 * </ul>
 */
@Component
public class MockBrain implements CompanionBrain {

    static final String DEGRADED_REPLY =
            "我是 Vega 的离线替身。管家大脑（workhorse sidecar）当前未就绪，" +
            "本次无法进行深度推理；巡检与汇报功能不受影响，待大脑恢复后会接续服务。";

    @Override
    public boolean healthy() {
        return true;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public PatrolResult runPatrol(PatrolRoutine routine, String scopeJson, int timeoutSeconds) {
        return PatrolResult.failed("mock brain: workhorse 未就绪，降级跳过真实巡检");
    }

    @Override
    public ChatHandle openChat(long projectId, String contextPrompt, ChatCallbacks callbacks) {
        return new ChatHandle() {
            @Override public String sessionId() { return "mock-session"; }
            @Override public String send(String userText) {
                callbacks.onDelta(DEGRADED_REPLY);
                callbacks.onEnd(DEGRADED_REPLY, false);
                return DEGRADED_REPLY;
            }
            @Override public void cancel() { /* no-op：mock 同步返回，无可打断的流 */ }
        };
    }
}
