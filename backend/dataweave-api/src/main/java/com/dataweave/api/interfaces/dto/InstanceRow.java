package com.dataweave.api.interfaces.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 周期实例行视图 — 契约① DTO。
 */
public record InstanceRow(
        UUID id,
        Long taskDefId,
        String taskDefName,
        Long workflowId,
        String runMode,
        String state,
        String bizDate,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs,
        String cronExpression
) {}
