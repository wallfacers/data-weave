package com.dataweave.master.companion.domain;

import java.time.LocalDateTime;

/**
 * 巡检执行历史（{@code patrol_run} 表，US4 可追溯）：每轮 run 一行。
 *
 * <p>幂等地基：{@code UNIQUE(routine_id, scheduled_fire_time)}——多 master 都尝试 INSERT 撞键者放弃（抄 cron_fire），
 * 配合 {@code state} 的 CAS 推进（调度不变量②）守单轮单执行。
 *
 * <p>状态机：{@code CLAIMED → RUNNING → {SUCCEEDED | FAILED | TIMEOUT}}（见 {@link PatrolRunStates}，禁回退）。
 */
public record PatrolRun(
        long id,
        long tenantId,
        long projectId,
        long routineId,
        String triggerType,         // SCHEDULED | MANUAL
        LocalDateTime scheduledFireTime,  // 计划触发时刻（幂等键的一部分）
        String state,               // 见 PatrolRunStates
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String summary,             // 本轮结论摘要
        String error,               // 失败原因（产"未完成"汇报时同源）
        int version,                // CAS 乐观锁
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
