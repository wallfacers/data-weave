package com.dataweave.master.application;

import java.util.List;
import java.util.UUID;

/**
 * 数据运维中心（data-ops-center）契约②：Stream A 暴露、Stream C 调用的共享 DTO/枚举。
 *
 * <p>纯数据载体，无行为。Stream C 在 interfaces 层据此组装 REST 响应（契约①）；
 * 写操作的闸门裁决（outcome）由 Stream C 在调用 Stream A 领域方法之前/之后填充，
 * Stream A 的领域方法本身只做领域动作、不产 outcome（design D2）。
 */
public final class OpsContracts {

    private OpsContracts() {}

    /** 批量操作类型。Stream C 按 op 映射到单实例领域方法：RERUN→rerunInstance / KILL→killTask / SET_SUCCESS→setSuccess。 */
    public enum BatchOp { RERUN, KILL, SET_SUCCESS }

    /** 周期实例行（筛选/分页投影）。durationMs 为 started→finished 毫秒（未结束为 null）。cronExpression 来自关联工作流定义（独立实例为 null）。 */
    public record InstanceRow(UUID id, Long taskDefId, String taskDefName, UUID workflowInstanceId,
                              String runMode, String state, String bizDate,
                              String startedAt, String finishedAt, Long durationMs,
                              String cronExpression, String env, String workflowName,
                              String scheduledFireTime) {}

    /**
     * 实例多维筛选条件（任一为空即不约束该维度）。page 从 0 起；size 上限由调用方夹取。
     * stateIn 为状态多选 CSV（与 state 单值并存，二者都给则都生效）；bizDate/startedAt 区间在对应列上闭区间过滤；
     * workerNodeCode 精确；failureReason 模糊。
     */
    public record InstanceQuery(String runMode, String state, Long taskId, String bizDate,
                                String stateIn, String bizDateFrom, String bizDateTo,
                                String startedAtFrom, String startedAtTo,
                                String workerNodeCode, String failureReason,
                                Long projectId, UUID workflowInstanceId, String keyword,
                                int page, int size) {
        /** 兼容旧 6 参构造（runMode/state/taskId/bizDate + 分页），扩展维度置空。 */
        public InstanceQuery(String runMode, String state, Long taskId, String bizDate, int page, int size) {
            this(runMode, state, taskId, bizDate, null, null, null, null, null, null, null, null, null, null, page, size);
        }
        /** 036 项目隔离：不含 projectId 的构造（向后兼容），projectId 置 null。 */
        public InstanceQuery(String runMode, String state, Long taskId, String bizDate,
                            String stateIn, String bizDateFrom, String bizDateTo,
                            String startedAtFrom, String startedAtTo,
                            String workerNodeCode, String failureReason,
                            int page, int size) {
            this(runMode, state, taskId, bizDate, stateIn, bizDateFrom, bizDateTo,
                 startedAtFrom, startedAtTo, workerNodeCode, failureReason, null, null, null, page, size);
        }
    }

    /** 分页结果通用包。 */
    public record PageResult<T>(List<T> items, long total, int page, int size) {}

    /**
     * 补数据发起请求。targetType ∈ "task"|"workflow"；日期 yyyy-MM-dd（含端点）；parallelism≥1。
     * downstreamTaskIds：用户勾选的血缘下游子集（空=只补目标自身）；最终目标=[自身]∪downstreamTaskIds。
     */
    public record BackfillRequest(String targetType, Long targetId, String dateStart, String dateEnd,
                                  boolean includeDownstream, int parallelism, List<Long> downstreamTaskIds) {
        public BackfillRequest {
            downstreamTaskIds = downstreamTaskIds == null ? List.of() : List.copyOf(downstreamTaskIds);
        }
        /** 兼容旧 6 参构造（无下游子集）。 */
        public BackfillRequest(String targetType, Long targetId, String dateStart, String dateEnd,
                               boolean includeDownstream, int parallelism) {
            this(targetType, targetId, dateStart, dateEnd, includeDownstream, parallelism, List.of());
        }
    }

    /** 下游任务预览项（血缘下游展开）：id/名称/类目节点(供前端解析路径)/层级(BFS 深度,从目标算起为 1)。 */
    public record DownstreamTaskView(Long id, String name, Long catalogNodeId, int level) {}

    /**
     * 补数据批次视图：实体字段 + 子实例聚合进度（success/failed/running 查询时算）。
     * activeDates/heldDates 为节流可观测（backfill-parallelism-throttle）：activeDates=当前放行（held=0）且未全部
     * 终态的 bizDate 数；heldDates=当前持有（held=1）待晋升的 bizDate 数。跑完后 heldDates=0。
     */
    public record BackfillRunView(UUID id, String targetType, Long targetId, String targetName,
                                  String dateStart, String dateEnd, int parallelism, boolean includeDownstream,
                                  String state, int total, int success, int failed, int running,
                                  String createdAt, int activeDates, int heldDates) {}

    /** 补数据批次详情：批次视图 + 其全部子实例。 */
    public record BackfillRunDetail(BackfillRunView run, List<InstanceRow> instances) {}

    /**
     * 批量操作单项结果。outcome ∈ EXECUTED|PENDING_APPROVAL|REJECTED（由 Stream C 据闸门裁决填充）；
     * approvalId 在 PENDING_APPROVAL 时非空；message 为可选附言（拒绝/失败原因）。
     */
    public record BatchItemResult(UUID id, String outcome, Long approvalId, String message) {}

    /** 批量操作汇总。requested=请求项数；accepted=进入执行/待批的项数（排除非法/未找到）。 */
    public record BatchResult(int requested, int accepted, List<BatchItemResult> results) {}

    /**
     * 任务流列表行（周期/手动运维列表投影）：定义字段 + 运行健康衍生。
     * recentTriggerResult 为该工作流最近一次实例状态（SUCCESS/FAILED/...，从未触发为 null）。
     * 时间字段为 ISO 字符串（未设为 null）。
     */
    public record WorkflowListRow(Long id, String name, String description, String cron, String status,
                                  Integer currentVersionNo, Integer hasDraftChange, String lastFireTime,
                                  Integer priority, Integer timeoutSec, String updatedAt, Long updatedBy,
                                  Long catalogNodeId, String recentTriggerResult,
                                  String nextTriggerTime) {}

    /**
     * 任务流列表筛选 + 分页条件（任一为空即不约束该维度）。page 从 0 起；size 上限由 service 夹取。
     * scheduleType 必填（CRON=周期 / MANUAL=手动）。recentResult ∈ SUCCESS|FAILED|NEVER（NEVER=从未触发）。
     */
    public record WorkflowQuery(String scheduleType, String keyword, Integer hasDraftChange,
                                String recentResult, Long catalogNodeId, Long createdBy,
                                Long projectId, int page, int size,
                                String priorityTier, String sortField, String sortDir) {}

    /**
     * 任务流实例列表行（筛选/分页投影）。durationMs 为 started→finished 毫秒（未结束为 null）。
     * workflowName 来自 workflow_def.name（correlated subquery）。
     * workflowVersionNo/cronExpression/scheduledFireTime 均为物化快照（免 JOIN）；scheduledFireTime
     * 仅 cron/fixed_rate 触发非空（手动/补数据为 null）。
     */
    public record WorkflowInstanceRow(UUID id, Long workflowId, String workflowName, String state,
                                      String bizDate, Integer priority, String triggerType,
                                      int totalTasks, int completedTasks, int failedTasks,
                                      String startedAt, String finishedAt, Long durationMs,
                                      String env,
                                      Integer workflowVersionNo, String cronExpression,
                                      String scheduledFireTime) {}

    /**
     * 任务流实例多维筛选条件（任一为空即不约束该维度）。page 从 0 起；size 上限由调用方夹取。
     * stateIn 为状态多选 CSV（与 state 单值并存，二者都给则都生效）；bizDate/startedAt 区间在对应列上闭区间过滤。
     */
    public record WorkflowInstanceQuery(String state, String stateIn, String triggerType,
                                         Long workflowId, String bizDate,
                                         String bizDateFrom, String bizDateTo,
                                         String startedAtFrom, String startedAtTo,
                                         String scheduledFireTimeFrom, String scheduledFireTimeTo,
                                         Long projectId, int page, int size) {}

    /** 实例 DAG 节点：DAG 拓扑位置 + 运行时状态叠加。 */
    public record InstanceDagNode(String nodeKey, String taskName, Long taskId, UUID taskInstanceId,
                                  String state, int attempt, String startedAt, String finishedAt,
                                  Long durationMs, double posX, double posY, String nodeType) {}

    /** 实例 DAG 边：端点用 nodeKey 引用。 */
    public record InstanceDagEdge(String fromNodeKey, String toNodeKey, String strength) {}

    /** 实例 DAG 完整视图：历史拓扑 + 全部节点运行时状态 + 边。 */
    public record InstanceDagView(UUID workflowInstanceId, String workflowName, int workflowVersionNo,
                                   String triggerType, String state, String bizDate,
                                   List<InstanceDagNode> nodes, List<InstanceDagEdge> edges,
                                   String env) {}

    /** 参数替换后的实际代码视图。 */
    public record ResolvedCodeView(UUID taskInstanceId, String rawContent, String resolvedContent,
                                    List<String> unresolvedPlaceholders, String runMode,
                                    boolean isOverride, String taskType) {}

    /** 参数替换后的实际配置视图。TEST 模式下 originalParamsJson/originalTimeoutSeconds 非空。 */
    public record ResolvedConfigView(UUID taskInstanceId, String taskType, int timeoutSeconds,
                                      String retryStrategy, String resourceLimit,
                                      String rawParamsJson, String resolvedParamsJson,
                                      List<String> unresolvedPlaceholders, String runMode,
                                      boolean isOverride, String originalParamsJson,
                                      Integer originalTimeoutSeconds, int taskVersionNo) {}
}
