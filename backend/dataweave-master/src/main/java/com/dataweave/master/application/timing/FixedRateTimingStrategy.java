package com.dataweave.master.application.timing;

import com.dataweave.master.application.TimingStrategy;
import com.dataweave.master.domain.WorkflowDef;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 固定频率计时策略：下一次触发 = 上一次计划触发时刻 + 固定间隔。
 * 首轮（无 prevScheduledFire）使用工作流创建时刻为基准。
 */
@Component
public class FixedRateTimingStrategy implements TimingStrategy {

    @Override
    public boolean supports(String scheduleType) {
        return "FIXED_RATE".equalsIgnoreCase(scheduleType);
    }

    @Override
    public LocalDateTime next(WorkflowDef wf, LocalDateTime base) {
        Long intervalMs = wf.getScheduleIntervalMs();
        if (intervalMs == null || intervalMs <= 0) {
            return null;
        }
        return base.plus(Duration.ofMillis(intervalMs));
    }
}
