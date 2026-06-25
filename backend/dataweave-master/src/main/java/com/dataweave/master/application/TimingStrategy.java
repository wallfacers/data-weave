package com.dataweave.master.application;

import com.dataweave.master.domain.WorkflowDef;

import java.time.LocalDateTime;

/**
 * 计时策略（移植 PowerJob 多计时策略思想）：按 {@code schedule_type} 计算严格大于 base 的下一次触发时刻。
 * 实现：CRON（Spring CronExpression，6 字段含秒）/ FIXED_RATE / FIXED_DELAY。
 */
public interface TimingStrategy {

    /** 是否处理该 schedule_type（CRON / FIXED_RATE / FIXED_DELAY）。 */
    boolean supports(String scheduleType);

    /**
     * 返回严格大于 {@code base} 的下一个触发时刻；无后续（如非法表达式）返回 {@code null}。
     * 调用方据此初始化/推进 {@code next_trigger_time}。
     */
    LocalDateTime next(WorkflowDef wf, LocalDateTime base);
}
