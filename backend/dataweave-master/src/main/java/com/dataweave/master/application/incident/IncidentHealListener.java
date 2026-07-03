package com.dataweave.master.application.incident;

import com.dataweave.master.application.WorkflowSucceededEvent;
import com.dataweave.master.quality.application.TaskSucceededEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Incident 自动愈合监听器（043）：消费任务成功 / 工作流成功事件，CAS 工单 → RESOLVED。
 *
 * <p>两条消费链：
 * <ol>
 *   <li>既有 {@link TaskSucceededEvent}（InstanceStateMachine SUCCESS CAS 后发布，现成）→ 按 taskId 愈合 TASK 类工单。</li>
 *   <li>新增 {@link WorkflowSucceededEvent}（WorkerReportService workflow SUCCESS 分支发布）→ 按 workflowInstanceId 愈合 WORKFLOW/SLA 类工单。</li>
 * </ol>
 *
 * <p>RESOLVED 窗口内复发（7 天）由 {@link IncidentService#openOrAttach} 的 CASE WHEN state='RESOLVED' THEN 'OPEN' 处理，
 * 本 listener 只在工单仍处于 OPEN/MITIGATING 时 CAS 愈合。
 *
 * <p>异常吞掉：愈合是辅助路径，不能因 listener 异常影响状态机/WorkerReportService 的正常流程。
 */
@Component
public class IncidentHealListener {

    private static final Logger log = LoggerFactory.getLogger(IncidentHealListener.class);

    private final IncidentService incidentService;

    public IncidentHealListener(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @EventListener(TaskSucceededEvent.class)
    public void on(TaskSucceededEvent event) {
        try {
            if (event.taskId() != null) {
                incidentService.healByTask(event.taskId(), event.tenantId());
            }
        } catch (Exception e) {
            log.error("[IncidentHeal] failed to heal by task {}", event.taskId(), e);
        }
    }

    @EventListener(WorkflowSucceededEvent.class)
    public void on(WorkflowSucceededEvent event) {
        try {
            if (event.workflowInstanceId() != null) {
                incidentService.healByWorkflowInstance(event.workflowInstanceId(), event.tenantId());
            }
        } catch (Exception e) {
            log.error("[IncidentHeal] failed to heal by workflow {}", event.workflowInstanceId(), e);
        }
    }
}
