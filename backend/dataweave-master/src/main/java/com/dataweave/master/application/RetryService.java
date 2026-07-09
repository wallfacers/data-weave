package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import org.springframework.stereotype.Service;

/**
 * 重试服务（task 2.8 / 060 计数双拆）：任务**业务**失败后按 task_def.retry_max 决定是否重试。
 *
 * <p>060（FR-009）：重试次数改比 {@code business_attempt}（仅"曾进入 RUNNING 后业务失败"才 +1，由
 * {@link WorkerReportService#reportFailed} 在 {@code started_at≠null} 时 {@code incrementBusinessAttempt}）。
 * infra 回收（WORKER_LOST/RESTART/下发失败）走 {@link InstanceStateMachine#reclaimInfra}，**不经此路、不烧 business_attempt**。
 *
 * <p>重试 = 把失败实例 CAS 回 WAITING（清空 worker/租约），下一轮调度由 {@link InstanceStateMachine#casDispatch}
 * 递增 attempt（纯下发纪元栅栏）。PREEMPTED 回炉不走此路（不计次，见 {@link PreemptionService}）。
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
     * 若仍有业务重试次数（{@code business_attempt <= retry_max}），则将该失败实例 CAS 回 WAITING 等待重派，
     * 返回 true；次数耗尽返回 false（调用方应置终态 FAILED）。
     *
     * <p>调用方（{@link WorkerReportService#reportFailed}）须先按 D2 规则在 {@code started_at≠null} 时
     * {@code incrementBusinessAttempt} 并把新值回填到 {@code ti.businessAttempt}。
     */
    public boolean scheduleRetry(TaskInstance ti) {
        int businessAttempt = ti.getBusinessAttempt() == null ? 0 : ti.getBusinessAttempt();
        int retryMax = retryMax(ti.getTaskId());
        if (businessAttempt > retryMax) {
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
