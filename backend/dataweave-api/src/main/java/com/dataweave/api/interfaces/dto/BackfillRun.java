package com.dataweave.api.interfaces.dto;

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
        String createdAt,
        int activeDates,        // 节流可观测（backfill-parallelism-throttle）：放行且未全部终态的 bizDate 数
        int heldDates           // 持有待晋升的 bizDate 数（跑完后为 0）
) {}
