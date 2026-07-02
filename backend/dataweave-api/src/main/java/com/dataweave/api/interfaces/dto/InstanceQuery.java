package com.dataweave.api.interfaces.dto;

import java.util.UUID;

/**
 * 周期实例筛选 + 分页参数 — 契约①。
 */
public record InstanceQuery(
        String runMode,
        String state,
        Long taskId,
        String bizDate,
        String stateIn,
        String bizDateFrom,
        String bizDateTo,
        String startedAtFrom,
        String startedAtTo,
        String workerNodeCode,
        String failureReason,
        Long projectId,
        UUID workflowInstanceId,
        int page,
        int size
) {
    public InstanceQuery {
        if (page <= 0) page = 1;
        if (size <= 0) size = 20;
    }

    /** 兼容旧 6 参构造（runMode/state/taskId/bizDate + 分页），扩展维度置空。 */
    public InstanceQuery(String runMode, String state, Long taskId, String bizDate, int page, int size) {
        this(runMode, state, taskId, bizDate, null, null, null, null, null, null, null, null, null, page, size);
    }
}
