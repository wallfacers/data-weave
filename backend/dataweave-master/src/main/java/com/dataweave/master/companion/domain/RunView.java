package com.dataweave.master.companion.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 巡检执行历史视图（契约 GET /routines/{id}/runs）：触发类型/计划时刻/状态/耗时/结论/关联汇报 id。
 */
public record RunView(
        long id,
        String triggerType,
        LocalDateTime scheduledFireTime,
        String state,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String summary,
        String error,
        List<Long> reportIds
) {
    public static RunView from(PatrolRun run, List<Long> reportIds) {
        return new RunView(run.id(), run.triggerType(), run.scheduledFireTime(), run.state(),
                run.startedAt(), run.finishedAt(), run.summary(), run.error(), reportIds);
    }
}
