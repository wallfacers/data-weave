package com.dataweave.master.companion.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 巡检执行历史视图（契约 GET /routines/{id}/runs，字段命名以契约 75d301ce 冻结为准）：
 * {@code id, triggerType, state, scheduledFireTime, startedAt, finishedAt, summary, error, reportIds}。
 */
public record RunView(
        long id,
        String triggerType,
        String state,
        LocalDateTime scheduledFireTime,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String summary,
        String error,
        List<Long> reportIds
) {
    public static RunView from(PatrolRun run, List<Long> reportIds) {
        return new RunView(run.id(), run.triggerType(), run.state(), run.scheduledFireTime(),
                run.startedAt(), run.finishedAt(), run.summary(), run.error(), reportIds);
    }
}
