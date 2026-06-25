package com.dataweave.master.application;

import java.time.LocalDateTime;

/**
 * 触发引擎（移植 PowerJob「预读窗口 + 到点精确触发」时序思想）：
 * 短周期扫描捞取 {@code next_trigger_time ≤ now+lookahead} 的工作流，按精确延迟压入进程内定时器，
 * 到点经 {@code cron_fire} 唯一键去重后委托 {@code WorkflowTriggerService.trigger()}（签名不变），
 * 触发后立即据 {@link TimingStrategy} 重算并持久化 {@code next_trigger_time}。
 */
public interface TriggerEngine {

    /** 由 {@code CronScheduler} 周期调用：扫描 + 将到期点装入精确触发器（幂等，重复装载同点不重复触发）。 */
    void scanAndArm(LocalDateTime now);
}
