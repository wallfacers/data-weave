package com.dataweave.master.infrastructure;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 051 就绪态物化：readiness_signal 表行数据。
 *
 * @param kind TERMINAL（上游终态触发）| RESET（上游 rerun/reset 回退）
 * @param upstreamInstanceId 触发信号的实例 id
 * @param workflowId 满足方 workflow_id（跨周期反查用）
 * @param workflowInstanceId 满足方 wf-instance id（同 DAG 后继定位用）
 * @param workflowNodeId 满足方 node id（edge/dependency 反查的 from/depend 端）
 * @param bizDate 满足方 bizDate（跨周期逆偏移用）
 */
public record ReadinessSignalRow(
        Long id,
        Long tenantId,
        Long projectId,
        String kind,
        UUID upstreamInstanceId,
        Long workflowId,
        UUID workflowInstanceId,
        Long workflowNodeId,
        String bizDate,
        int processed,
        LocalDateTime createdAt,
        LocalDateTime processedAt
) {
    /** 创建 TERMINAL 信号。 */
    public static ReadinessSignalRow terminal(Long tenantId, Long projectId,
                                               UUID upstreamInstanceId,
                                               Long workflowId, UUID workflowInstanceId,
                                               Long workflowNodeId, String bizDate) {
        return new ReadinessSignalRow(null, tenantId, projectId, "TERMINAL",
                upstreamInstanceId, workflowId, workflowInstanceId, workflowNodeId, bizDate,
                0, null, null);
    }

    /** 创建 RESET 信号。 */
    public static ReadinessSignalRow reset(Long tenantId, Long projectId,
                                            UUID upstreamInstanceId,
                                            Long workflowId, UUID workflowInstanceId,
                                            Long workflowNodeId, String bizDate) {
        return new ReadinessSignalRow(null, tenantId, projectId, "RESET",
                upstreamInstanceId, workflowId, workflowInstanceId, workflowNodeId, bizDate,
                0, null, null);
    }
}
