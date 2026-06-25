package com.dataweave.master.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Cron 调度外壳（distributed-cron-trigger）：每 {@code scheduler.cron-scan-interval-ms}（默认 15s）触发一次
 * 短周期扫描，委托 {@link TriggerEngine#scanAndArm} 把预读窗口内的到期点压入进程内精确触发器到点触发。
 *
 * <p>去重真相仍是 {@code cron_fire} 唯一键（多 master/分片零协调）；下游实例创建、计时策略、misfire 归一
 * 均下沉到 {@link TriggerEngine} / {@link TimingStrategy}，本类只负责按周期驱动扫描。时间以
 * {@link SchedulerClock}（DB 服务端时间，FR-010）为基准。
 */
@Component
public class CronScheduler {

    private static final Logger log = LoggerFactory.getLogger(CronScheduler.class);

    private final TriggerEngine triggerEngine;
    private final SchedulerClock clock;

    public CronScheduler(TriggerEngine triggerEngine, SchedulerClock clock) {
        this.triggerEngine = triggerEngine;
        this.clock = clock;
    }

    @Scheduled(fixedRateString = "${scheduler.cron-scan-interval-ms:15000}")
    public void tick() {
        try {
            triggerEngine.scanAndArm(clock.now());
        } catch (Exception e) {
            log.error("[CronScheduler] 扫描失败：{}", e.getMessage(), e);
        }
    }
}
