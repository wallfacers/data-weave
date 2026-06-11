package com.dataweave.master.application;

import java.util.UUID;

/**
 * 任务下发接缝（design D10）：master 把一个已 DISPATCHED 的任务实例交给 worker 执行。
 *
 * <p>all-in-one 模式由进程内实现直接执行（{@code InProcessTaskExecutionGateway}）；
 * distributed 模式由 WebClient 调 worker 的 {@code POST /internal/worker/exec}（task 3.2）。
 * 下发为「写前置之后、事务之外」的副作用：调用前实例已 CAS 置 DISPATCHED 并落租约。
 */
public interface TaskExecutionGateway {

    /** 下发一个任务实例到其 worker 执行。下发本身失败应抛异常，由调用方 CAS 回 WAITING 重派。 */
    void dispatch(DispatchCommand cmd);

    /**
     * 下发指令（执行所需的全部上下文，worker 据此运行并按 (instanceId, attempt) 幂等去重）。
     *
     * @param taskInstanceId 任务实例 id
     * @param attempt        本次尝试序号（幂等钥匙之一）
     * @param workerNodeCode 目标节点
     * @param taskId         任务定义 id
     * @param taskVersionNo  运行版本（null=TEST 草稿）
     * @param runMode        NORMAL / TEST
     * @param bizDate        业务日期（注入任务环境，幂等钥匙之一）
     * @param content        执行内容（已发布版本快照或草稿）
     * @param timeoutSeconds 超时秒数（≤ 0 表示不限时）
     * @param taskType       任务类型（SQL / SHELL / …），执行端按此选执行器
     */
    record DispatchCommand(UUID taskInstanceId, int attempt, String workerNodeCode, Long taskId,
                           Integer taskVersionNo, String runMode, String bizDate, String content,
                           int timeoutSeconds, String taskType) {
    }
}
