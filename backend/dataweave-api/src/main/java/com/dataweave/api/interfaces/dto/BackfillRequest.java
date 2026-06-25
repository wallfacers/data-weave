package com.dataweave.api.interfaces.dto;

import java.util.List;

/**
 * 补数据请求 — 契约①。
 *
 * <p>{@code downstreamTaskIds}：用户在血缘下游里勾选的子集(空=只补目标自身)。最终补数据目标为
 * [目标自身] ∪ downstreamTaskIds。{@code includeDownstream} 保留作向后兼容(true 且无显式集合=全下游)。
 */
public record BackfillRequest(
        String targetType,      // "task" | "workflow"
        Long targetId,
        String dateStart,       // yyyy-MM-dd
        String dateEnd,         // yyyy-MM-dd
        boolean includeDownstream,
        int parallelism,
        List<Long> downstreamTaskIds
) {
    public BackfillRequest {
        if (parallelism <= 0) parallelism = 1;
        if (parallelism > 10) parallelism = 10;
        downstreamTaskIds = downstreamTaskIds == null ? List.of() : List.copyOf(downstreamTaskIds);
    }

    /** 兼容旧 6 参构造（无下游子集，如 MCP 工具入口）。 */
    public BackfillRequest(String targetType, Long targetId, String dateStart, String dateEnd,
                           boolean includeDownstream, int parallelism) {
        this(targetType, targetId, dateStart, dateEnd, includeDownstream, parallelism, List.of());
    }
}
