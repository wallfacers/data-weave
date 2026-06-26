package com.dataweave.master.application.timing;

import com.dataweave.master.application.TimingStrategy;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 固定延迟计时策略：下一次触发 = 上一次实例完成时刻 + 固定间隔。
 * <p>
 * 与 FIXED_RATE 的区别：FIXED_RATE 以计划触发时刻为基准（与实例执行时长无关），
 * FIXED_DELAY 以实际完成时刻为基准（上一实例跑完才起算间隔）。
 * <p>
 * lastCompletion 来源：查该工作流最近一条已完成（SUCCESS/FAILED/CANCELLED）的
 * workflow_instance 的 finished_at；无历史记录则用工作流创建时刻为基准。
 */
@Component
public class FixedDelayTimingStrategy implements TimingStrategy {

    private final WorkflowInstanceRepository workflowInstanceRepository;

    public FixedDelayTimingStrategy(WorkflowInstanceRepository workflowInstanceRepository) {
        this.workflowInstanceRepository = workflowInstanceRepository;
    }

    @Override
    public boolean supports(String scheduleType) {
        return "FIXED_DELAY".equalsIgnoreCase(scheduleType);
    }

    @Override
    public LocalDateTime next(WorkflowDef wf, LocalDateTime base) {
        Long intervalMs = wf.getScheduleIntervalMs();
        if (intervalMs == null || intervalMs <= 0) {
            return null;
        }
        // "base" 由调用方传入：调用方先查最近完成的实例的 finished_at，
        // 无则用 created_at。本策略仅做 base + interval。
        return base.plus(Duration.ofMillis(intervalMs));
    }
}
