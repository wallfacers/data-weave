package com.dataweave.master.application;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 调度统一时钟。多 master 依赖 SKIP LOCKED + CAS 去重保证正确性，不要求时钟严格同步。
 */
@Component
public class SchedulerClock {

    /** 当前时间。 */
    public LocalDateTime now() {
        return LocalDateTime.now();
    }
}
