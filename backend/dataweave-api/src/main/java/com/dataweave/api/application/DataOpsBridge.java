package com.dataweave.api.application;

import com.dataweave.api.interfaces.dto.*;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskInstance;

import java.util.List;
import java.util.UUID;

/**
 * 契约②的桥接接口：Stream A 暴露的运维服务签名。
 *
 * <p>Stream C 在接口层通过此桥接调用 Stream A 的领域服务，编译期使用桩实现，
 * 集成期替换为委托到 {@code OpsService}（及可能的 {@code BackfillService}）的真实现。
 *
 * <p>注意：写操作的闸门由 Stream C 的 controller 层前置（构造 {@code ActionRequest}
 * → {@code GatedActionService.submit}），桥接的 service 方法只做领域动作。
 */
public interface DataOpsBridge {

    /** 提交补数据：校验 + 落 backfill_run + 生成子实例 + 触发调度。 */
    BackfillRun submitBackfill(BackfillRequest req);

    /** 补数据下游影响范围预览：沿血缘解析目标任务的下游任务(M1 仅 task 目标,workflow 返回空)。 */
    List<com.dataweave.master.application.OpsContracts.DownstreamTaskView> previewDownstream(
            String targetType, Long targetId);

    /** 批量操作：逐个执行 RERUN | KILL | SET_SUCCESS。 */
    BatchResult batchOp(List<UUID> instanceIds, BatchOp op);

    /** 置成功：CAS 推进 SUCCESS + 唤醒下游 WAITING。 */
    TaskInstance setSuccess(UUID instanceId);


    /**
     * 节点级 DAG 冻结/解冻（ops-center-publish-boundary）。
     * @param instanceId 空=定义级（后续每个 cron 实例跳该节点）；非空=实例级（仅该实例）
     */
    void setNodeFrozen(Long workflowId, String nodeKey, UUID instanceId, boolean frozen);

    /** 多维筛选 + 分页查询周期实例。 */
    Page<InstanceRow> queryInstances(InstanceQuery q);

    /** 查询单个补数据运行记录 + 子实例。 */
    BackfillRun backfillRun(UUID runId);

    /** 分页查询补数据运行列表。036 项目隔离：按 projectId 过滤。 */
    List<BackfillRun> backfillRuns(int page, int size, Long projectId);

    /** 多维筛选 + 分页查询补数据运行列表（state CSV 多选 / targetName 模糊 / targetType / bizDate 区间 / createdBy）。 */
    Page<BackfillRun> queryBackfillRuns(String state, String targetName, String targetType,
                                        String bizDateFrom, String bizDateTo, Long createdBy,
                                        Long projectId, int page, int size);

    /** 补数据运行关联的子实例列表。 */
    List<InstanceRow> backfillRunInstances(UUID runId);

    /** 多维筛选 + 分页查询任务流实例。 */
    Page<com.dataweave.master.application.OpsContracts.WorkflowInstanceRow> queryWorkflowInstances(
            com.dataweave.master.application.OpsContracts.WorkflowInstanceQuery q);

    /** 获取实例级 DAG（历史拓扑 + 运行时状态叠加）。 */
    com.dataweave.master.application.OpsContracts.InstanceDagView getInstanceDag(UUID workflowInstanceId);

    /** 获取任务实例参数替换后的实际代码。 */
    com.dataweave.master.application.OpsContracts.ResolvedCodeView getResolvedCode(UUID taskInstanceId);

    /** 获取任务实例参数替换后的实际配置。 */
    com.dataweave.master.application.OpsContracts.ResolvedConfigView getResolvedConfig(UUID taskInstanceId);
}
