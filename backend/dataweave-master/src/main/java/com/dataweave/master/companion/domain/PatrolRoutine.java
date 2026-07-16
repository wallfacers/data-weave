package com.dataweave.master.companion.domain;

import java.time.LocalDateTime;

/**
 * 巡检例程（{@code patrol_routine} 表）：某领域的计划巡检定义——领域/启停/cron/范围/超时。
 *
 * <p>平台可治理任务（US4）：管理员可经 {@code PATCH /api/companion/routines/{id}} 启停、调频、改范围。
 * 项目内按领域唯一（{@code UNIQUE(project_id, domain)}）；{@code enabled=false} 的例程不参与巡检（FR-006）。
 */
public record PatrolRoutine(
        long id,
        long tenantId,
        long projectId,
        String domain,            // 见 PatrolDomains
        boolean enabled,          // SMALLINT 0/1
        String cronExpression,    // Spring CronExpression 6 字段（含秒）
        String scopeJson,         // 领域范围参数（目录/标签过滤），JSON；可空
        int timeoutSeconds,       // 单轮超时（R7 默认 120s）
        Long createdBy,
        Long updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int version               // 乐观锁（US4 PATCH）
) {
}
