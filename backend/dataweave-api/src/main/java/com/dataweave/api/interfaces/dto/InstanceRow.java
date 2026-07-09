package com.dataweave.api.interfaces.dto;

import java.util.UUID;

/**
 * 周期实例行视图 — 契约① DTO。
 * 时间字段为 ISO-8601 UTC 字符串（与 workflow-instance 契约一致），前端可直接解析为本地时间。
 */
public record InstanceRow(
        UUID id,
        Long taskDefId,
        String taskDefName,
        Long workflowId,
        UUID workflowInstanceId,
        String runMode,
        String state,
        String bizDate,
        String startedAt,
        String finishedAt,
        Long durationMs,
        String cronExpression,
        String env,
        String taskType,
        String workflowName,
        String scheduledFireTime,
        String triggerType
) {}
