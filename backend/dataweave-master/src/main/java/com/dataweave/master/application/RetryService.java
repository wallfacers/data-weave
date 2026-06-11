package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import org.springframework.stereotype.Service;

/**
 * 重试服务（task 2.8）：任务失败后按 task_def.retry_max 决定是否重试。
 *
 * <p>重试 = 把失败实例 CAS 回 WAITING（清空 worker/租约），下一轮调度由 {@link InstanceStateMachine#casDispatch}
 * 递增 attempt。PREEMPTED 回炉不走此路（不计次，见 {@link PreemptionService}）。
 * 退避：v1 立即回队由调度轮次自然节流；精细退避（延迟可调度时刻）留待后续。
 */
@Service
public class RetryService {

    private final InstanceStateMachine stateMachine;
    private final TaskDefRepository taskDefRepository;

    public RetryService(InstanceStateMachine stateMachine, TaskDefRepository taskDefRepository) {
        this.stateMachine = stateMachine;
        this.taskDefRepository = taskDefRepository;
    }

    /**
     * 若仍有重试次数，则将该（运行中/已下发）失败实例 CAS 回 WAITING 等待重派，返回 true；
     * 次数耗尽返回 false（调用方应置终态 FAILED）。
     */
    public boolean scheduleRetry(TaskInstance ti) {
        int attempt = ti.getAttempt() == null ? 0 : ti.getAttempt();
        int retryMax = retryMax(ti.getTaskId());
        // attempt 为本次失败的尝试序号（首次=1）；已用重试 = attempt-1；仍可重试当 attempt <= retryMax。
        if (attempt > retryMax) {
            return false;
        }
        return stateMachine.casRequeue(ti.getId(), ti.getState());
    }

    private int retryMax(Long taskId) {
        if (taskId == null) {
            return 0;
        }
        return taskDefRepository.findById(taskId)
                .map(TaskDef::getRetryMax)
                .filter(v -> v != null)
                .orElse(0);
    }
}
