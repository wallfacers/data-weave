package com.dataweave.api.interfaces.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 补数据请求 — 契约①。
 */
public record BackfillRequest(
        String targetType,      // "task" | "workflow"
        Long targetId,
        String dateStart,       // yyyy-MM-dd
        String dateEnd,         // yyyy-MM-dd
        boolean includeDownstream,
        int parallelism
) {
    public BackfillRequest {
        if (parallelism <= 0) parallelism = 1;
        if (parallelism > 10) parallelism = 10;
    }
}
