package com.dataweave.master.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@link SchedulingPolicy} 默认实现（design D5）：
 * <ul>
 *   <li><b>有效优先级</b> = 声明优先级 − ⌊等待秒数 / agingStep⌋，下限 0。等待越久数值越小（越优先），防饥饿。
 *       TEST 试跑额外抬升（再减一档）。</li>
 *   <li><b>选节点</b> = least-loaded：剩余槽位最多者；并列取 load_avg 更低者。</li>
 * </ul>
 * agingStep 可配；调小 = aging 更快（趋于人人平等），由「最长等待者年龄」指标观测调优。
 */
@Component
public class DefaultSchedulingPolicy implements SchedulingPolicy {

    private final long agingStepSeconds;

    public DefaultSchedulingPolicy(
            @Value("${scheduler.aging-step-seconds:60}") long agingStepSeconds) {
        this.agingStepSeconds = Math.max(1, agingStepSeconds);
    }

    @Override
    public int effectivePriority(Candidate c, LocalDateTime now) {
        int declared = c.declaredPriority();
        long waitedSeconds = c.waitingSince() == null ? 0
                : Math.max(0, Duration.between(c.waitingSince(), now).getSeconds());
        long agingBonus = waitedSeconds / agingStepSeconds;
        int eff = (int) (declared - agingBonus);
        if (c.test()) {
            eff -= 1; // TEST 天然高优
        }
        return Math.max(0, eff);
    }

    @Override
    public Optional<NodeLoad> place(Candidate c, List<NodeLoad> candidates) {
        return candidates.stream()
                .filter(n -> n.free() > 0)
                .max(Comparator.comparingInt(NodeLoad::free)
                        .thenComparingDouble(n -> -safeLoad(n)));
    }

    private double safeLoad(NodeLoad n) {
        Double load = n.node().getLoadAvg();
        return load == null ? 0.0 : load;
    }
}
