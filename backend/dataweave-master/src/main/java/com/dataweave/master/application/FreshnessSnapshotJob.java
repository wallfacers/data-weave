package com.dataweave.master.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每日新鲜度快照定时任务。
 *
 * <p>每天凌晨 02:00 执行，为每个 (tenant, project) 拍摄当前新鲜度快照，
 * 写入 freshness_task_daily（每任务）和 freshness_daily_snapshot（项目聚合）。
 * 使用 pg_try_advisory_lock 保证多 master 实例下仅执行一次。
 */
@Component
public class FreshnessSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(FreshnessSnapshotJob.class);
    private static final int LOCK_ID = 42; // advisory lock id for freshness snapshot

    private final FreshnessService freshnessService;
    private final JdbcTemplate jdbcTemplate;

    public FreshnessSnapshotJob(FreshnessService freshnessService, JdbcTemplate jdbcTemplate) {
        this.freshnessService = freshnessService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 每日 02:00 执行快照任务。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void takeDailySnapshot() {
        // PG advisory lock：多 master 实例仅一个执行
        Boolean locked = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_ID);
        if (locked == null || !locked) {
            log.debug("FreshnessSnapshotJob skipped — lock not acquired (another master is running it)");
            return;
        }
        try {
            log.info("FreshnessSnapshotJob starting");
            // 枚举所有有任务的项目
            List<ProjectPair> projects = jdbcTemplate.query(
                    "SELECT DISTINCT tenant_id, project_id FROM task_def WHERE deleted = 0",
                    (rs, n) -> new ProjectPair(rs.getLong("tenant_id"), rs.getLong("project_id")));

            int projectCount = 0;
            for (ProjectPair pp : projects) {
                try {
                    freshnessService.takeSnapshot(pp.tenantId, pp.projectId);
                    projectCount++;
                } catch (Exception e) {
                    log.error("Freshness snapshot failed for tenant={} project={}", pp.tenantId, pp.projectId, e);
                }
            }

            // 清理 90 天前数据
            freshnessService.cleanupOldSnapshots();
            log.info("FreshnessSnapshotJob completed — {} projects snapshotted", projectCount);
        } catch (Exception e) {
            log.error("FreshnessSnapshotJob failed", e);
        } finally {
            jdbcTemplate.update("SELECT pg_advisory_unlock(?)", LOCK_ID);
        }
    }

    private record ProjectPair(Long tenantId, Long projectId) {}
}
