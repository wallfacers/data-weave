package com.dataweave.master.companion.application;

import java.util.Optional;

import com.dataweave.master.companion.domain.CompanionBrain;
import com.dataweave.master.companion.infrastructure.MockBrain;
import com.dataweave.master.companion.infrastructure.WorkhorseBrainClient;
import org.springframework.stereotype.Component;

/**
 * 大脑选择策略（健康探测 + 降级路由）。
 *
 * <p>两种用途的降级语义不同（FR-016 / SC-007）：
 * <ul>
 *   <li><b>巡检</b>（{@link #forPatrol}）：workhorse 不可用则降级 {@link MockBrain}，
 *       其 {@code runPatrol} 永远 failed → {@code PatrolService} 兜底产 INFO"未完成"汇报，零静默丢失。</li>
 *   <li><b>对话</b>（{@link #forChat}）：仅 workhorse；不可用返回 empty → {@code CompanionChatService}
 *       抛 {@code companion.brain_unavailable}（明确的本地化降级提示，非静默、非空白）。</li>
 * </ul>
 */
@Component
public class CompanionBrainSelector {

    private final WorkhorseBrainClient workhorse;
    private final MockBrain mock;

    public CompanionBrainSelector(WorkhorseBrainClient workhorse, MockBrain mock) {
        this.workhorse = workhorse;
        this.mock = mock;
    }

    /** 巡检用大脑：workhorse 在线则用之，否则降级 mock。 */
    public CompanionBrain forPatrol() {
        return workhorse.healthy() ? workhorse : mock;
    }

    /** 对话用大脑：仅 workhorse；不可用返回 empty（调用方抛 companion.brain_unavailable）。 */
    public Optional<CompanionBrain> forChat() {
        return workhorse.healthy() ? Optional.of(workhorse) : Optional.empty();
    }

    /** workhorse 是否就绪（统一健康出口）。 */
    public boolean healthy() {
        return workhorse.healthy();
    }
}
