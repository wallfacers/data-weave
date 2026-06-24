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

    /** 周期实例行（筛选/分页投影）。durationMs 为 started→finished 毫秒（未结束为 null）。 */
    public record InstanceRow(UUID id, Long taskDefId, String taskDefName, UUID workflowInstanceId,
                              String runMode, String state, String bizDate,
                              String startedAt, String finishedAt, Long durationMs) {}

    /** 实例多维筛选条件（任一为空即不约束该维度）。page 从 0 起；size 上限由调用方夹取。 */
    public record InstanceQuery(String runMode, String state, Long taskId, String bizDate,
                                int page, int size) {}

    /** 分页结果通用包。 */
    public record PageResult<T>(List<T> items, long total, int page, int size) {}

    /** 补数据发起请求。targetType ∈ "task"|"workflow"；日期 yyyy-MM-dd（含端点）；parallelism≥1。 */
    public record BackfillRequest(String targetType, Long targetId, String dateStart, String dateEnd,
                                  boolean includeDownstream, int parallelism) {}

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
}
