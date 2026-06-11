package com.dataweave.master.application;

import com.dataweave.master.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Cron 调度引擎：每分钟扫描可调度工作流，到期则触发执行。
 */
@Component
public class CronScheduler {

    private static final Logger log = LoggerFactory.getLogger(CronScheduler.class);
    private static final DateTimeFormatter BIZ_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final WorkflowDefRepository workflowDefRepository;
    private final WorkflowNodeRepository workflowNodeRepository;
    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final TaskInstanceRepository taskInstanceRepository;

    public CronScheduler(WorkflowDefRepository workflowDefRepository,
                         WorkflowNodeRepository workflowNodeRepository,
                         WorkflowInstanceRepository workflowInstanceRepository,
                         TaskInstanceRepository taskInstanceRepository) {
        this.workflowDefRepository = workflowDefRepository;
        this.workflowNodeRepository = workflowNodeRepository;
        this.workflowInstanceRepository = workflowInstanceRepository;
        this.taskInstanceRepository = taskInstanceRepository;
    }

    @Scheduled(fixedRate = 60000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        List<WorkflowDef> schedulable = workflowDefRepository
                .findByScheduleTypeAndStatusAndDeleted("CRON", "ONLINE", 0);

        int triggered = 0;
        for (WorkflowDef wf : schedulable) {
            try {
                if (shouldFire(wf, now)) {
                    fireWorkflow(wf, now);
                    triggered++;
                }
            } catch (Exception e) {
                log.error("[CronScheduler] failed to trigger workflow id={}: {}", wf.getId(), e.getMessage(), e);
            }
        }
        log.info("[CronScheduler] tick: scanned={}, triggered={}", schedulable.size(), triggered);
    }

    /** 判断工作流是否应触发：cron 有效 + 在调度窗口内 + 到达触发时间。 */
    private boolean shouldFire(WorkflowDef wf, LocalDateTime now) {
        if (wf.getCron() == null || wf.getCron().isBlank()) return false;

        // 调度窗口检查
        if (wf.getScheduleStart() != null && now.isBefore(wf.getScheduleStart())) return false;
        if (wf.getScheduleEnd() != null && now.isAfter(wf.getScheduleEnd())) return false;

        // 解析 cron
        CronExpression cron;
        try {
            cron = CronExpression.parse(wf.getCron());
        } catch (Exception e) {
            log.warn("[CronScheduler] invalid cron '{}' for workflow id={}", wf.getCron(), wf.getId());
            return false;
        }

        // 计算上次触发后的下次触发时间
        LocalDateTime lastFire = wf.getLastFireTime();
        LocalDateTime nextFire;
        if (lastFire == null) {
            // 从未触发过：用 createdAt 作为基准
            nextFire = cron.next(wf.getCreatedAt() != null ? wf.getCreatedAt() : now.minusDays(1));
        } else {
            nextFire = cron.next(lastFire);
        }

        return !now.isBefore(nextFire);
    }

    /** 触发工作流执行。 */
    private void fireWorkflow(WorkflowDef wf, LocalDateTime now) {
        // 获取工作流节点
        List<WorkflowNode> nodes = workflowNodeRepository.findByWorkflowId(wf.getId());
        if (nodes.isEmpty()) {
            log.warn("[CronScheduler] workflow id={} has no nodes, skipping", wf.getId());
            return;
        }

        // 创建工作流实例
        WorkflowInstance wi = new WorkflowInstance();
        wi.setTenantId(wf.getTenantId());
        wi.setProjectId(wf.getProjectId());
        wi.setWorkflowId(wf.getId());
        wi.setWorkflowVersionNo(wf.getCurrentVersionNo());
        wi.setTriggerType("CRON");
        wi.setState("RUNNING");
        wi.setBizDate(now.minusDays(1).format(BIZ_DATE_FMT));
        wi.setTotalTasks(nodes.size());
        wi.setCompletedTasks(0);
        wi.setFailedTasks(0);
        wi.setStartedAt(now);
        wi.setCreatedAt(now);
        wi.setUpdatedAt(now);
        wi.setDeleted(0);
        wi.setVersion(0L);
        WorkflowInstance savedWi = workflowInstanceRepository.save(wi);

        // 为每个节点创建任务实例
        for (WorkflowNode node : nodes) {
            TaskInstance ti = new TaskInstance();
            ti.setTenantId(wf.getTenantId());
            ti.setProjectId(wf.getProjectId());
            ti.setWorkflowInstanceId(savedWi.getId());
            ti.setWorkflowNodeId(node.getId());
            ti.setTaskId(node.getTaskId());
            ti.setRunMode("NORMAL");
            ti.setState("NOT_RUN");
            ti.setAttempt(0);
            ti.setCreatedAt(now);
            ti.setUpdatedAt(now);
            ti.setDeleted(0);
            ti.setVersion(0L);
            taskInstanceRepository.save(ti);
        }

        // 更新 last_fire_time
        wf.setLastFireTime(now);
        wf.setUpdatedAt(now);
        workflowDefRepository.save(wf);

        log.info("[CronScheduler] fired workflow id={} name='{}' instanceId={} nodes={}",
                wf.getId(), wf.getName(), savedWi.getId(), nodes.size());
    }
}
