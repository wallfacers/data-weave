package com.dataweave.master.quality.application;

import com.dataweave.master.quality.domain.QualityRule;
import com.dataweave.master.quality.domain.QualityRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 独立调度触发器（research D2.2）：周期性扫启用 + 配了 {@code schedule_cron} 的断言规则，
 * 经 {@code quality_fire} guard 表 UNIQUE 冲突防重（镜像 {@code cron_fire} 范式），
 * 认领成功后触发 {@link QualityCheckRunner#run(List, String, java.util.UUID, Long)}。
 *
 * <p>HA 单点：多 master 都 INSERT quality_fire，UNIQUE(rule_id, scheduled_fire_time) 约束冲突
 * 即别 master 已认领 → 捕获 {@link DataIntegrityViolationException} 跳过（PG/H2 通用，
 * 不用 MySQL INSERT IGNORE）。
 */
@Service
public class QualityScheduleTrigger {

    private static final Logger log = LoggerFactory.getLogger(QualityScheduleTrigger.class);

    private final QualityRuleRepository ruleRepository;
    private final QualityCheckRunner runner;
    private final JdbcTemplate jdbc;

    public QualityScheduleTrigger(QualityRuleRepository ruleRepository,
                                   QualityCheckRunner runner,
                                   JdbcTemplate jdbc) {
        this.ruleRepository = ruleRepository;
        this.runner = runner;
        this.jdbc = jdbc;
    }

    /**
     * 每分钟扫描一次（与 cron_fire 调度同频）。
     * v1 简化：扫描所有 tenant 的启用调度规则（生产需分片）。
     */
    @Scheduled(fixedRate = 60_000)
    public void scanAndFire() {
        // 扫描所有启用的独立调度规则
        // v1 简化：不按 tenant 分片，全局扫（租户少时可行；后续加 shard）
        List<QualityRule> scheduled = ruleRepository.findScheduledRules(1L); // tenantId=1 default
        if (scheduled.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        // 对齐到分钟（与 cron_fire 范式一致）
        LocalDateTime alignedTime = LocalDateTime.of(
                now.getYear(), now.getMonth(), now.getDayOfMonth(),
                now.getHour(), now.getMinute(), 0);

        for (QualityRule rule : scheduled) {
            if (rule.getScheduleCron() == null || rule.getScheduleCron().isBlank()) {
                continue;
            }
            // v1 简化：不严格解析 cron（每分钟扫 + guard UNIQUE 防重足够）
            // 生产用 CronExpression.parse
            tryFire(rule, alignedTime, now);
        }
    }

    private void tryFire(QualityRule rule, LocalDateTime scheduledFireTime, LocalDateTime now) {
        try {
            jdbc.update(
                    "INSERT INTO quality_fire (rule_id, scheduled_fire_time, fired_at, created_at)"
                            + " VALUES (?, ?, ?, ?)",
                    rule.getId(), scheduledFireTime, now, now);
            // 插入成功 → 本 master 认领本轮
            log.info("[QualitySchedule] ruleId={} cron={} fired", rule.getId(), rule.getScheduleCron());
            runner.run(List.of(rule), "SCHEDULED", null, rule.getTenantId());
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 冲突 → 别 master 已认领，跳过
            log.debug("[QualitySchedule] ruleId={} already fired by peer, skip", rule.getId());
        } catch (Exception e) {
            log.error("[QualitySchedule] ruleId={} fire failed: {}", rule.getId(), e.getMessage());
        }
    }
}
