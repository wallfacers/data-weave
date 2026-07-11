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
     * @param datasourceId   任务绑定的业务数据源 id（SQL 执行连库用；null=未绑定，执行端回退模拟）
     * @param locale          触发者 BCP-47 locale tag（如 en-US/zh-CN）；null=老数据/占位实例，消费端兜底 zh-CN
     * @param sparkMode       SPARK 任务内容形态（pyspark / spark-sql / jar）；非 SPARK 任务为 null
     * @param jarRef          SPARK jar 形态的 application jar 引用（本地路径 / 资产标识）；其它形态 null
     * @param mainClass       SPARK jar 形态的 --class 主类；其它形态 null
     * @param engineMode      通用引擎任务子模式（FLINK: sql|jar；DATAX/SEATUNNEL: null）；非引擎任务为 null
     * @param engineJarRef    通用引擎 jar 形态的 application jar 引用（FLINK jar 用）；其它形态 null
     * @param engineMainClass 通用引擎 jar 形态的 --class 主类（FLINK jar 用）；其它形态 null
     * @param longRunning       062：外部托管长驻作业标记（task_instance.long_running 快照）；true → 引擎执行器走 detached 长驻分支
     * @param externalJobHandle 062：已有外部作业句柄（task_instance.external_job_handle）；非空 → 引擎执行器 reattach 重连而非重复提交
     */
    record DispatchCommand(UUID taskInstanceId, int attempt, String workerNodeCode, Long taskId,
                           Integer taskVersionNo, String runMode, String bizDate, String content,
                           int timeoutSeconds, String taskType, Long datasourceId, String locale,
                           String sparkMode, String jarRef, String mainClass,
                           String engineMode, String engineJarRef, String engineMainClass,
                           boolean longRunning, String externalJobHandle) {
    }
}
