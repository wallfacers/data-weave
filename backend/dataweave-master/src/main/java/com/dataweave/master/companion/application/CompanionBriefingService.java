package com.dataweave.master.companion.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

import com.dataweave.master.companion.domain.Briefing;
import com.dataweave.master.companion.domain.CompanionEvent;
import com.dataweave.master.companion.domain.PatrolRoutine;
import com.dataweave.master.companion.infrastructure.JdbcPatrolReportRepository;
import com.dataweave.master.companion.infrastructure.JdbcPatrolRoutineRepository;
import com.dataweave.master.companion.infrastructure.JdbcPatrolRunRepository;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * 巡检概况（T011）：今日轮次数 / 未关闭异常数 / 启用例程最近的下次触发时间。
 *
 * <p>数字永远现算（非缓存，同 incident 战况播报一致性契约）。{@link #computeAndNotify} 在
 * todayRuns/openAnomalies 变化时发 {@code briefing} 事件（nextPatrolAt 随时间漂移不单独触发事件，
 * 由 snapshot 与相关变更点顺带刷新）。
 */
@Component
public class CompanionBriefingService {

    private final JdbcPatrolRunRepository runRepo;
    private final JdbcPatrolReportRepository reportRepo;
    private final JdbcPatrolRoutineRepository routineRepo;
    private final CompanionEventPublisher publisher;
    private final ConcurrentHashMap<Long, int[]> lastCounts = new ConcurrentHashMap<>();   // [todayRuns, openAnomalies]

    public CompanionBriefingService(JdbcPatrolRunRepository runRepo, JdbcPatrolReportRepository reportRepo,
                                    JdbcPatrolRoutineRepository routineRepo, CompanionEventPublisher publisher) {
        this.runRepo = runRepo;
        this.reportRepo = reportRepo;
        this.routineRepo = routineRepo;
        this.publisher = publisher;
    }

    public Briefing compute(long tenantId, long projectId) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        int todayRuns = runRepo.countToday(tenantId, projectId, todayStart);
        int openAnomalies = reportRepo.countOpenAnomalies(tenantId, projectId);
        return new Briefing(todayRuns, openAnomalies, nextPatrolAt(tenantId, projectId));
    }

    /** 现算概况并在 todayRuns/openAnomalies 变化时发 briefing 事件。 */
    public Briefing computeAndNotify(long tenantId, long projectId) {
        Briefing b = compute(tenantId, projectId);
        int[] prev = lastCounts.put(projectId, new int[]{b.todayRuns(), b.openAnomalies()});
        if (prev == null || prev[0] != b.todayRuns() || prev[1] != b.openAnomalies()) {
            publisher.publish(projectId, new CompanionEvent.BriefingChanged(b));
        }
        return b;
    }

    /** 启用例程中最近的下次触发时间（Spring CronExpression；无启用例程或解析失败返回 null）。 */
    LocalDateTime nextPatrolAt(long tenantId, long projectId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nearest = null;
        for (PatrolRoutine r : routineRepo.findByProject(tenantId, projectId)) {
            if (!r.enabled()) continue;
            try {
                LocalDateTime next = CronExpression.parse(r.cronExpression()).next(now);
                if (next != null && (nearest == null || next.isBefore(nearest))) nearest = next;
            } catch (IllegalArgumentException ignore) {
                // cron 非法：跳过该例程，不拖垮概况
            }
        }
        return nearest;
    }
}
