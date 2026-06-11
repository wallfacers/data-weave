package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import com.dataweave.master.domain.WorkflowNode;
import com.dataweave.master.domain.WorkflowNodeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 工作流/任务触发与实例创建（task 2.7/2.10/2.11 共用）。
 *
 * <p>创建实例时所有节点统一置 WAITING——「等待不占资源」：上游未就绪的节点由调度内核的可运行门
 * （上游 SUCCESS 才认领）自然挡住，不必显式 NOT_RUN→WAITING 解锁过渡。创建后发布唤醒触发即时调度。
 */
@Service
public class WorkflowTriggerService {

    private final WorkflowNodeRepository nodeRepository;
    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final TaskInstanceRepository taskInstanceRepository;
    private final TaskDefRepository taskDefRepository;
    private final EventBus eventBus;

    public WorkflowTriggerService(WorkflowNodeRepository nodeRepository,
                                  WorkflowInstanceRepository workflowInstanceRepository,
                                  TaskInstanceRepository taskInstanceRepository,
                                  TaskDefRepository taskDefRepository,
                                  EventBus eventBus) {
        this.nodeRepository = nodeRepository;
        this.workflowInstanceRepository = workflowInstanceRepository;
        this.taskInstanceRepository = taskInstanceRepository;
        this.taskDefRepository = taskDefRepository;
        this.eventBus = eventBus;
    }

    /**
     * 触发一个工作流：建 workflow_instance + 各节点 task_instance（全 WAITING），发布唤醒。
     *
     * @return 新建 workflow_instance 的 id；工作流无节点抛 {@link IllegalStateException}
     */
    public UUID trigger(WorkflowDef wf, String triggerType, String bizDate, Integer priorityOverride) {
        List<WorkflowNode> nodes = nodeRepository.findByWorkflowId(wf.getId());
        if (nodes.isEmpty()) {
            throw new IllegalStateException("工作流无节点，无法触发：" + wf.getId());
        }
        LocalDateTime now = LocalDateTime.now();

        WorkflowInstance wi = new WorkflowInstance();
        wi.setTenantId(wf.getTenantId());
        wi.setProjectId(wf.getProjectId());
        wi.setWorkflowId(wf.getId());
        wi.setWorkflowVersionNo(wf.getCurrentVersionNo());
        wi.setTriggerType(triggerType);
        wi.setState(InstanceStates.RUNNING);
        wi.setPriority(priorityOverride != null ? priorityOverride
                : (wf.getPriority() != null ? wf.getPriority() : 5));
        wi.setBizDate(bizDate);
        wi.setTotalTasks(nodes.size());
        wi.setCompletedTasks(0);
        wi.setFailedTasks(0);
        wi.setStartedAt(now);
        wi.setCreatedAt(now);
        wi.setUpdatedAt(now);
        wi.setDeleted(0);
        wi.setVersion(0L);
        WorkflowInstance savedWi = workflowInstanceRepository.save(wi);

        for (WorkflowNode node : nodes) {
            Integer versionNo = taskDefRepository.findById(node.getTaskId())
                    .map(TaskDef::getCurrentVersionNo).orElse(null);
            TaskInstance ti = newTaskInstance(wf.getTenantId(), wf.getProjectId(), now);
            ti.setWorkflowInstanceId(savedWi.getId());
            ti.setWorkflowNodeId(node.getId());
            ti.setTaskId(node.getTaskId());
            ti.setTaskVersionNo(versionNo != null && versionNo > 0 ? versionNo : null);
            ti.setRunMode("NORMAL");
            ti.setState(InstanceStates.WAITING);
            ti.setBizDate(bizDate);
            taskInstanceRepository.save(ti);
        }

        wake();
        return savedWi.getId();
    }

    /**
     * 单任务测试运行（design D9）：脱离工作流、跑草稿内容（task_version_no=null）、run_mode=TEST，
     * 不入正式统计。返回 task_instance id。
     */
    public UUID triggerTestRun(Long taskId, String bizDate) {
        TaskDef task = taskDefRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务不存在：" + taskId));
        LocalDateTime now = LocalDateTime.now();
        TaskInstance ti = newTaskInstance(task.getTenantId(), task.getProjectId(), now);
        ti.setWorkflowInstanceId(null);
        ti.setWorkflowNodeId(null);
        ti.setTaskId(taskId);
        ti.setTaskVersionNo(null);          // 草稿
        ti.setRunMode("TEST");
        ti.setState(InstanceStates.WAITING);
        ti.setBizDate(bizDate);
        TaskInstance saved = taskInstanceRepository.save(ti);
        wake();
        return saved.getId();
    }

    private TaskInstance newTaskInstance(Long tenantId, Long projectId, LocalDateTime now) {
        TaskInstance ti = new TaskInstance();
        ti.setTenantId(tenantId);
        ti.setProjectId(projectId);
        ti.setAttempt(0);
        ti.setCreatedAt(now);
        ti.setUpdatedAt(now);
        ti.setDeleted(0);
        ti.setVersion(0L);
        return ti;
    }

    private void wake() {
        eventBus.publish(InstanceStates.WAKE_CHANNEL, "trigger");
    }
}
