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
import java.util.Locale;
import java.util.UUID;

/**
 * 工作流/任务触发与实例创建（task 2.7/2.10/2.11 共用）。
 *
 * <p>创建实例时所有节点统一置 WAITING——「等待不占资源」：上游未就绪的节点由调度内核的可运行门
 * （上游 SUCCESS 才认领）自然挡住，不必显式 NOT_RUN→WAITING 解锁过渡。创建后发布唤醒触发即时调度。
 *
 * <p>每个实例落 {@code locale}（触发者 BCP-47 tag）——供任务运行日志 banner 按触发者 locale 渲染
 * （i18n 规则②）。cron 触发由调用方传 {@link com.dataweave.master.i18n.Messages#DEFAULT_LOCALE}。
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
     * @param locale 触发者 locale（agent 触发传 agent locale；cron 触发传默认中文）
     * @return 新建 workflow_instance 的 id；工作流无节点抛 {@link IllegalStateException}
     */
    public UUID trigger(WorkflowDef wf, String triggerType, String bizDate, Integer priorityOverride,
                        Locale locale) {
        List<WorkflowNode> nodes = nodeRepository.findByWorkflowIdAndDeleted(wf.getId(), 0);
        if (nodes.isEmpty()) {
            throw new IllegalStateException("工作流无节点，无法触发：" + wf.getId());
        }
        LocalDateTime now = LocalDateTime.now();

        // 虚拟节点（zero-load）物化即 SUCCESS，计入已完成数。
        int virtualCount = (int) nodes.stream()
                .filter(WorkflowTriggerService::isVirtual).count();

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
        wi.setCompletedTasks(virtualCount);
        wi.setFailedTasks(0);
        wi.setStartedAt(now);
        wi.setCreatedAt(now);
        wi.setUpdatedAt(now);
        wi.setDeleted(0);
        wi.setVersion(0L);
        WorkflowInstance savedWi = workflowInstanceRepository.save(wi);

        for (WorkflowNode node : nodes) {
            TaskInstance ti = newTaskInstance(wf.getTenantId(), wf.getProjectId(), now, locale);
            ti.setWorkflowInstanceId(savedWi.getId());
            ti.setWorkflowNodeId(node.getId());
            ti.setRunMode("NORMAL");
            ti.setBizDate(bizDate);
            if (isVirtual(node)) {
                // VIRTUAL：零负载锚点，物化即成功——不绑 task、不下发、不占槽。
                ti.setTaskId(null);
                ti.setTaskVersionNo(null);
                ti.setState(InstanceStates.SUCCESS);
                ti.setStartedAt(now);
                ti.setFinishedAt(now);
            } else {
                Integer versionNo = node.getTaskId() != null
                        ? taskDefRepository.findById(node.getTaskId())
                                .map(TaskDef::getCurrentVersionNo).orElse(null)
                        : null;
                ti.setTaskId(node.getTaskId());
                ti.setTaskVersionNo(versionNo != null && versionNo > 0 ? versionNo : null);
                ti.setState(InstanceStates.WAITING);
            }
            taskInstanceRepository.save(ti);
        }

        wake();
        return savedWi.getId();
    }

    /** VIRTUAL（zero-load）节点判定。null/缺省 node_type 视为 TASK。 */
    private static boolean isVirtual(WorkflowNode node) {
        return "VIRTUAL".equals(node.getNodeType());
    }

    /**
     * 单任务测试运行（design D9）：脱离工作流、跑草稿内容（task_version_no=null）、run_mode=TEST，
     * 不入正式统计。返回 task_instance id。
     */
    public UUID triggerTestRun(Long taskId, String bizDate, Locale locale) {
        return triggerTestRun(taskId, bizDate, null, null, null, locale);
    }

    /**
     * 单任务测试运行（task-run-decouple）：可携带编辑器临时内容（含未保存改动）。
     *
     * <p>{@code contentOverride} 非空时写入 {@code task_instance.content_override}，调度认领时优先于
     * task_def 草稿被取用（见 {@code SchedulerKernel.contentOf}），**不写 task_def**——满足「不管存没存，
     * 跑编辑器最新内容」。为空则回退当前 DB 草稿（如从实例列表 rerun 历史 TEST）。
     *
     * @param contentOverride 编辑器临时脚本内容（可空）
     * @param paramsOverride  编辑器临时调度参数 JSON（可空，与 content 同源用于占位符解析）
     * @param typeOverride    编辑器临时任务类型（可空，非空则覆盖 task_def.type 选执行器）
     * @param locale          触发者 locale（banner 按此渲染）
     */
    public UUID triggerTestRun(Long taskId, String bizDate, String contentOverride,
                               String paramsOverride, String typeOverride, Locale locale) {
        TaskDef task = taskDefRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务不存在：" + taskId));
        LocalDateTime now = LocalDateTime.now();
        TaskInstance ti = newTaskInstance(task.getTenantId(), task.getProjectId(), now, locale);
        ti.setWorkflowInstanceId(null);
        ti.setWorkflowNodeId(null);
        ti.setTaskId(taskId);
        ti.setTaskVersionNo(null);          // 草稿
        ti.setContentOverride(contentOverride != null && !contentOverride.isBlank() ? contentOverride : null);
        ti.setParamsOverride(paramsOverride != null && !paramsOverride.isBlank() ? paramsOverride : null);
        ti.setTypeOverride(typeOverride != null && !typeOverride.isBlank() ? typeOverride : null);
        ti.setRunMode("TEST");
        ti.setState(InstanceStates.WAITING);
        ti.setBizDate(bizDate);
        TaskInstance saved = taskInstanceRepository.save(ti);
        wake();
        return saved.getId();
    }

    /**
     * 单任务正式手动运行（manual-run-trigger）：脱离工作流、跑**已发布版本**
     * （task_version_no=current_version_no）、run_mode=NORMAL，计入正式运维统计。返回 task_instance id。
     *
     * <p>与 {@link #triggerTestRun} 互为镜像——TEST 跑草稿（version=null）不计统计；
     * 本方法跑已发布版本、计统计，是「正式实例」语义。草稿/未发布的拦截在闸门之前（controller），
     * 此处兜底：current_version_no 缺失则按未发布处理（version 落 null）。
     */
    public UUID triggerManualTaskRun(Long taskId, String bizDate, Locale locale) {
        TaskDef task = taskDefRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务不存在：" + taskId));
        Integer versionNo = task.getCurrentVersionNo();
        LocalDateTime now = LocalDateTime.now();
        TaskInstance ti = newTaskInstance(task.getTenantId(), task.getProjectId(), now, locale);
        ti.setWorkflowInstanceId(null);
        ti.setWorkflowNodeId(null);
        ti.setTaskId(taskId);
        ti.setTaskVersionNo(versionNo != null && versionNo > 0 ? versionNo : null);  // 跑已发布版本
        ti.setRunMode("NORMAL");
        ti.setState(InstanceStates.WAITING);
        ti.setBizDate(bizDate);
        TaskInstance saved = taskInstanceRepository.save(ti);
        wake();
        return saved.getId();
    }

    private TaskInstance newTaskInstance(Long tenantId, Long projectId, LocalDateTime now, Locale locale) {
        TaskInstance ti = new TaskInstance();
        ti.setTenantId(tenantId);
        ti.setProjectId(projectId);
        ti.setAttempt(0);
        ti.setLocale(locale != null ? locale.toLanguageTag() : null);
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
