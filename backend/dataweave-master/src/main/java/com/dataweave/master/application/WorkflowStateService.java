package com.dataweave.master.application;

import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import com.dataweave.master.domain.TaskInstanceRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作流实例状态聚合服务（两级状态机的「聚合」侧）。
 *
 * <p>按 design-data-model.md §4 矩阵，由一个 workflow_instance 下所有 task_instance（节点实例）的
 * 状态聚合出 workflow_instance.state。**只统计 run_mode=NORMAL 的节点**，TEST 试跑不参与。
 *
 * <p>节点态：NOT_RUN / WAITING / RUNNING / SUCCESS / FAILED / STOPPED。
 * 聚合优先级（自上而下取首个命中）：STOPPED → RUNNING → FAILED → SUCCESS → WAITING → NOT_RUN。
 *
 * <p>MVP 阶段调度执行为 mock，但本聚合按真实语义计算；后期接真实 Master↔Worker 调度时，
 * 节点态来源从 mock 推进切换为真实回调，聚合规则不变。
 */
@Service
public class WorkflowStateService {

    private final TaskInstanceRepository taskInstanceRepository;
    private final WorkflowInstanceRepository workflowInstanceRepository;

    public WorkflowStateService(TaskInstanceRepository taskInstanceRepository,
                                WorkflowInstanceRepository workflowInstanceRepository) {
        this.taskInstanceRepository = taskInstanceRepository;
        this.workflowInstanceRepository = workflowInstanceRepository;
    }

    /**
     * 纯函数：按 §4 矩阵从节点实例集合聚合工作流态。入参可含 TEST 节点，内部会过滤掉。
     *
     * @param nodes 该工作流实例下的全部 task_instance
     * @return 聚合后的 workflow_instance.state
     */
    public String aggregate(List<TaskInstance> nodes) {
        boolean hasRunning = false;
        boolean hasFailed = false;
        boolean hasWaiting = false;
        boolean hasNotRun = false;
        boolean hasSuccess = false;
        boolean hasStopped = false;
        int normalCount = 0;
        int stoppedCount = 0;

        for (TaskInstance n : nodes) {
            if (n == null || !"NORMAL".equals(n.getRunMode())) {
                continue; // TEST 试跑不参与聚合
            }
            normalCount++;
            String s = n.getState() == null ? "NOT_RUN" : n.getState();
            switch (s) {
                case "RUNNING" -> hasRunning = true;
                case "FAILED" -> hasFailed = true;
                case "WAITING" -> hasWaiting = true;
                case "SUCCESS" -> hasSuccess = true;
                case "STOPPED" -> { hasStopped = true; stoppedCount++; }
                default -> hasNotRun = true; // NOT_RUN
            }
        }

        if (normalCount == 0) {
            return "NOT_RUN"; // 实例已建但无（正式）节点
        }
        // 优先级 1：人工停止 —— 整流被停（所有节点 STOPPED）
        if (stoppedCount == normalCount) {
            return "STOPPED";
        }
        // 优先级 2：运行中 —— 有节点在跑；或部分成功且仍有待跑节点（推进中）
        if (hasRunning || (hasSuccess && (hasWaiting || hasNotRun))) {
            return "RUNNING";
        }
        // 优先级 3：失败 —— 有失败且无在跑（卡死、无法继续推进）
        if (hasFailed) {
            return "FAILED";
        }
        // 优先级 4：成功 —— 所有节点均 SUCCESS（无 FAILED/RUNNING/WAITING/NOT_RUN/STOPPED）
        if (hasSuccess && !hasWaiting && !hasNotRun && !hasStopped) {
            return "SUCCESS";
        }
        // 优先级 5：等待 —— 已触发但尚未开始任何节点（节点多为 WAITING）
        if (hasWaiting) {
            return "WAITING";
        }
        // 优先级 6：未运行 —— 全部 NOT_RUN
        return "NOT_RUN";
    }

    /**
     * 计算指定工作流实例的聚合态并落库（仅当与当前持久态不同才 UPDATE）。
     *
     * @return 聚合后的状态；找不到实例时返回 {@code Optional.empty()}
     */
    public Optional<String> computeAndUpdate(UUID workflowInstanceId) {
        Optional<WorkflowInstance> opt = workflowInstanceRepository.findById(workflowInstanceId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        WorkflowInstance wi = opt.get();
        List<TaskInstance> nodes = taskInstanceRepository.findByWorkflowInstanceId(workflowInstanceId);
        String state = aggregate(nodes);
        if (!state.equals(wi.getState())) {
            wi.setState(state);
            wi.setUpdatedAt(LocalDateTime.now());
            workflowInstanceRepository.save(wi);
        }
        return Optional.of(state);
    }
}
