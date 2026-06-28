package com.dataweave.master.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * cron_fire 归档清理（FR-011）：低频删除 fired_at 早于保留窗口的历史记录，
 * 防止护栏表无界增长拖慢去重 INSERT。清理与触发路径解耦。
 */
@Component
public class CronFireReaper {

    private static final Logger log = LoggerFactory.getLogger(CronFireReaper.class);

    private final JdbcTemplate jdbc;
    private final int retentionDays;

    public CronFireReaper(JdbcTemplate jdbc,
                          @Value("${scheduler.cron-fire-retention-days:30}") int retentionDays) {
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
    }

    /** 每天凌晨 3:17 执行（避开整点触发高峰），删除 retentionDays 天前的 cron_fire 记录。 */
    @Scheduled(cron = "17 3 * * * *")
    void reap() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = jdbc.update("DELETE FROM cron_fire WHERE fired_at IS NOT NULL AND fired_at < ?", cutoff);
        if (deleted > 0) {
            log.info("[CronFireReaper] 清理 {} 条 cron_fire（fired_at < {}）", deleted, cutoff);
        }
    }
}
