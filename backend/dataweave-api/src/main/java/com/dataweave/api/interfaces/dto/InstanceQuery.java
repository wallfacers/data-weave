package com.dataweave.api.interfaces.dto;

/**
 * 周期实例筛选 + 分页参数 — 契约①。
 */
public record InstanceQuery(
        String runMode,
        String state,
        Long taskId,
        String bizDate,
        int page,
        int size
) {
    public InstanceQuery {
        if (page <= 0) page = 1;
        if (size <= 0) size = 20;
    }
}
