package com.dataweave.master.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 调度统一时间真相来源（FR-010）：以数据库服务端时间为基准，降低多 master 机器时钟漂移导致的
 * 触发延迟/补偿偏差。cron 去重键由持久化的 {@code next_trigger_time}（DB 值）推导、跨 master 确定，
 * 时钟漂移主要影响“何时扫描/触发”而非去重正确性；本时钟把这一影响也收敛到单一来源。
 *
 * <p>取数失败时回退到本机时间，保证调度不被一次 DB 抖动卡死。
 */
@Component
public class SchedulerClock {

    private static final Logger log = LoggerFactory.getLogger(SchedulerClock.class);

    private final JdbcTemplate jdbc;

    public SchedulerClock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 当前 DB 服务端时间；失败回退本机时间。 */
    public LocalDateTime now() {
        try {
            LocalDateTime t = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
            return t != null ? t : LocalDateTime.now();
        } catch (Exception e) {
            log.debug("[SchedulerClock] DB 时间取数失败，回退本机时间：{}", e.getMessage());
            return LocalDateTime.now();
        }
    }
}
