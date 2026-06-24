package com.dataweave.api.interfaces.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 补数据运行记录 — 契约①。
 */
public record BackfillRun(
        UUID id,
        String targetType,
        Long targetId,
        String targetName,
        String dateStart,
        String dateEnd,
        int parallelism,
        String state,           // RUNNING | SUCCESS | FAILED | PARTIAL
        int total,
        int success,
        int failed,
        int running,
        LocalDateTime createdAt
) {}
