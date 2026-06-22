package com.dataweave.master.application;

import com.dataweave.master.domain.CronFire;
import com.dataweave.master.domain.CronFireRepository;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Cron 调度器（design D4，task 2.7）：每个 master 都扫到期工作流并尝试触发；触发前先向护栏表
 * {@code cron_fire(workflow_id, scheduled_fire_time)} 插入记录，撞复合唯一键即放弃——多 master 零协调防重。
 *
 * <p>misfire 策略可配：{@code fire_once}（默认，恢复后补触发最近一个错过点一次）/ {@code skip}
 * （错过多个点则跳过、仅推进基准）。实例创建委托 {@link WorkflowTriggerService}，统一走调度内核执行。
 */
@Component
public class CronScheduler {

    private static final Logger log = LoggerFactory.getLogger(CronScheduler.class);
    private static final DateTimeFormatter BIZ_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final WorkflowDefRepository workflowDefRepository;
    private final CronFireRepository cronFireRepository;
    private final WorkflowTriggerService triggerService;
    private final String misfirePolicy;

    public CronScheduler(WorkflowDefRepository workflowDefRepository,
                         CronFireRepository cronFireRepository,
                         WorkflowTriggerService triggerService,
                         @Value("${scheduler.cron-misfire:fire_once}") String misfirePolicy) {
        this.workflowDefRepository = workflowDefRepository;
        this.cronFireRepository = cronFireRepository;
        this.triggerService = triggerService;
        this.misfirePolicy = misfirePolicy;
    }

    @Scheduled(fixedRate = 60000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        List<WorkflowDef> schedulable = workflowDefRepository
                .findByScheduleTypeAndStatusAndDeleted("CRON", "ONLINE", 0);
        int triggered = 0;
        for (WorkflowDef wf : schedulable) {
            try {
                if (tryFire(wf, now)) {
                    triggered++;
                }
            } catch (Exception e) {
                log.error("[CronScheduler] 触发工作流 id={} 失败：{}", wf.getId(), e.getMessage(), e);
            }
        }
        if (!schedulable.isEmpty()) {
            log.info("[CronScheduler] tick: 扫描={}, 触发={}", schedulable.size(), triggered);
        }
    }

    /** 计算到期触发点并按 misfire 策略+护栏表防重触发一次。返回是否本 master 触发成功。 */
    private boolean tryFire(WorkflowDef wf, LocalDateTime now) {
        if (wf.getCron() == null || wf.getCron().isBlank()) {
            return false;
        }
        if (wf.getScheduleStart() != null && now.isBefore(wf.getScheduleStart())) {
            return false;
        }
        if (wf.getScheduleEnd() != null && now.isAfter(wf.getScheduleEnd())) {
            return false;
        }
        CronExpression cron;
        try {
            cron = CronExpression.parse(wf.getCron());
        } catch (Exception e) {
            log.warn("[CronScheduler] 非法 cron '{}' workflow id={}", wf.getCron(), wf.getId());
            return false;
        }

        LocalDateTime base = wf.getLastFireTime() != null ? wf.getLastFireTime()
                : (wf.getCreatedAt() != null ? wf.getCreatedAt() : now.minusMinutes(1));
        // 找 (base, now] 区间内最近的触发点，并统计错过点数
        LocalDateTime due = null;
        int missed = 0;
        LocalDateTime cursor = cron.next(base);
        while (cursor != null && !cursor.isAfter(now)) {
            due = cursor;
            missed++;
            cursor = cron.next(cursor);
        }
        if (due == null) {
            return false;  // 未到触发点
        }
        // misfire：skip 且错过多个点 → 仅推进基准不触发
        if ("skip".equalsIgnoreCase(misfirePolicy) && missed > 1) {
            wf.setLastFireTime(due);
            wf.setUpdatedAt(now);
            workflowDefRepository.save(wf);
            log.info("[CronScheduler] workflow id={} 错过 {} 个触发点，misfire=skip 跳过至 {}", wf.getId(), missed, due);
            return false;
        }

        // 护栏表防重：插入 (workflow_id, due) 成功者拥有本次触发
        CronFire guard = new CronFire(wf.getId(), due);
        guard.setCreatedAt(now);
        try {
            cronFireRepository.save(guard);
        } catch (DataIntegrityViolationException dup) {
            return false;  // 别的 master 已触发本点
        }

        UUID wiId = triggerService.trigger(wf, "CRON", due.minusDays(1).format(BIZ_DATE_FMT), wf.getPriority(), Messages.DEFAULT_LOCALE);
        guard.setWorkflowInstanceId(wiId);
        guard.setFiredAt(LocalDateTime.now());
        cronFireRepository.save(guard);

        wf.setLastFireTime(due);
        wf.setUpdatedAt(now);
        workflowDefRepository.save(wf);
        log.info("[CronScheduler] 触发 workflow id={} name='{}' 触发点={} 实例={}",
                wf.getId(), wf.getName(), due, wiId);
        return true;
    }
}
