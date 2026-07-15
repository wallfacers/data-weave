package com.dataweave.master.companion.application;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.dataweave.master.companion.domain.CompanionBrain;
import com.dataweave.master.companion.domain.CompanionEvent;
import com.dataweave.master.companion.domain.PatrolResult;
import com.dataweave.master.companion.domain.PatrolRoutine;
import com.dataweave.master.companion.domain.PatrolRun;
import com.dataweave.master.companion.domain.PatrolRunStates;
import com.dataweave.master.companion.domain.ReportSeverities;
import com.dataweave.master.companion.domain.ReportView;
import com.dataweave.master.companion.infrastructure.JdbcPatrolReportRepository;
import com.dataweave.master.companion.infrastructure.JdbcPatrolRoutineRepository;
import com.dataweave.master.companion.infrastructure.JdbcPatrolRunRepository;
import com.dataweave.master.i18n.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 巡检编排（T019）：单轮 run 的执行 + 手动触发入口。
 *
 * <ul>
 *   <li>{@link #executeRun}：对一条 RUNNING 的 run 调 {@link CompanionBrain#runPatrol}，
 *       结构化结果落 patrol_report；解析失败/超时/brain 不可用 → 兜底 INFO"未完成"汇报（SC-007 零静默丢失）。
 *       同领域 10 分钟窗口内异常聚合（FR-011）。完成后刷新 state/briefing。</li>
 *   <li>{@link #triggerManual}：治理手动触发入口（US4），创建 MANUAL run + 立即 CAS RUNNING + 异步执行。</li>
 * </ul>
 *
 * <p>brain 外呼在调用方事务外（调度不变量④）：executeRun 自身不开事务，仅靠 CAS 推进 run 状态。
 */
@Service
public class PatrolService {

    private static final Logger log = LoggerFactory.getLogger(PatrolService.class);
    private static final int AGGREGATE_WINDOW_MINUTES = 10;

    private final JdbcPatrolRunRepository runRepo;
    private final JdbcPatrolRoutineRepository routineRepo;
    private final JdbcPatrolReportRepository reportRepo;
    private final CompanionBrainSelector brainSelector;
    private final CompanionEventPublisher publisher;
    private final CompanionStateResolver stateResolver;
    private final CompanionBriefingService briefingService;
    private final ExecutorService patrolExecutor;

    public PatrolService(JdbcPatrolRunRepository runRepo, JdbcPatrolRoutineRepository routineRepo,
                         JdbcPatrolReportRepository reportRepo, CompanionBrainSelector brainSelector,
                         CompanionEventPublisher publisher, CompanionStateResolver stateResolver,
                         CompanionBriefingService briefingService,
                         @Value("${companion.patrol.threads:2}") int threads) {
        this.runRepo = runRepo;
        this.routineRepo = routineRepo;
        this.reportRepo = reportRepo;
        this.brainSelector = brainSelector;
        this.publisher = publisher;
        this.stateResolver = stateResolver;
        this.briefingService = briefingService;
        this.patrolExecutor = Executors.newFixedThreadPool(Math.max(1, threads),
                r -> { Thread t = new Thread(r, "companion-patrol"); t.setDaemon(true); return t; });
    }

    /** 异步执行一条 run（调度器/手动触发调用；事务外 brain 外呼）。 */
    public void executeRunAsync(long runId) {
        patrolExecutor.submit(() -> {
            try {
                executeRun(runId);
            } catch (Exception e) {
                log.warn("[Patrol] executeRun {} 异常: {}", runId, e.toString());
            }
        });
    }

    /** 同步执行一条 run（仅 RUNNING 态才推进；reaper 已置 TIMEOUT 则跳过，由兜底逻辑产未完成）。 */
    public void executeRun(long runId) {
        PatrolRun run = runRepo.findById(runId).orElse(null);
        if (run == null) return;
        if (!PatrolRunStates.RUNNING.equals(run.state())) {
            return;   // 已终态（reaper TIMEOUT 兜底单独处理）或异常态，幂等跳过
        }
        PatrolRoutine routine = routineRepo.findById(run.routineId()).orElse(null);
        if (routine == null) {
            runRepo.casFinish(runId, PatrolRunStates.FAILED, null, "例程已删除");
            return;
        }

        CompanionBrain brain = brainSelector.forPatrol();
        PatrolResult result;
        try {
            result = brain.runPatrol(routine, routine.scopeJson(), routine.timeoutSeconds());
        } catch (Exception e) {
            result = PatrolResult.failed("brain 异常: " + e.getMessage());
        }

        // M3：先 CAS 终态（单赢，与 reaper markTimeout 互斥），赢得终态才 publishReport——
        // 此前 publishReport 在 casFinish 之前且忽略返回值，与 reaper 竞态会一 run 双汇报。
        boolean won;
        if (result.ok()) {
            won = runRepo.casFinish(runId, PatrolRunStates.SUCCEEDED, result.title(), null);
        } else {
            won = runRepo.casFinish(runId, PatrolRunStates.FAILED, null, safe(result.error()));
        }
        if (!won) return;   // reaper 已赢 TIMEOUT 终态并产超时汇报，本次让出，不双汇报（reaper 自带 state/briefing 刷新）

        if (result.ok()) {
            publishReport(run, routine, result.severity(), result.title(), result.summary(), result.detailJson());
        } else {
            // 兜底"未完成"汇报（SC-007）：brain 不可用/超时/解析失败都不静默丢失
            publishReport(run, routine, ReportSeverities.INFO, "巡检未完成",
                    "巡检未完成：" + safe(result.error()), "{}");
        }
        // 刷新形态/概况（异常增删驱动 alert/briefing 变化）
        stateResolver.resolveAndNotify(run.tenantId(), run.projectId());
        briefingService.computeAndNotify(run.tenantId(), run.projectId());
    }

    /** reaper 兜底：run 被标 TIMEOUT 后产一条 INFO"未完成"汇报（不重复 brain 外呼）。 */
    public void produceTimeoutReport(long runId) {
        PatrolRun run = runRepo.findById(runId).orElse(null);
        if (run == null || !PatrolRunStates.TIMEOUT.equals(run.state())) return;
        PatrolRoutine routine = routineRepo.findById(run.routineId()).orElse(null);
        String domain = routine != null ? routine.domain() : "UNKNOWN";
        publishReport(run, routine, ReportSeverities.INFO, "巡检未完成",
                "巡检超时未完成：" + safe(run.error()), "{\"domain\":\"" + domain + "\"}");
        stateResolver.resolveAndNotify(run.tenantId(), run.projectId());
        briefingService.computeAndNotify(run.tenantId(), run.projectId());
    }

    /**
     * 手动触发入口（US4 POST /routines/{id}/trigger）：创建 MANUAL run（UNIQUE 单赢）+ 立即 CAS RUNNING + 异步执行。
     * 返回 runId；若同例程已有同触发时刻 run（极小概率）抛 {@code companion.routine_busy}。
     */
    public long triggerManual(long tenantId, long projectId, long routineId) {
        PatrolRoutine routine = routineRepo.findById(routineId)
                .filter(r -> r.tenantId() == tenantId && r.projectId() == projectId)
                .orElseThrow(() -> new BizException("companion.routine_not_found", routineId));
        PatrolRun run = runRepo.tryClaimCreate(tenantId, projectId, routineId, "MANUAL", LocalDateTime.now())
                .orElseThrow(() -> new BizException("companion.routine_busy"));
        runRepo.casStart(run.id());
        executeRunAsync(run.id());
        return run.id();
    }

    /** 落汇报 + 发 report:created 事件；同领域异常聚合（FR-011，10 分钟窗口 aggregate_count +1）。 */
    private void publishReport(PatrolRun run, PatrolRoutine routine, String severity, String title,
                               String summary, String detailJson) {
        String domain = routine != null ? routine.domain() : "UNKNOWN";
        if (ReportSeverities.isAnomaly(severity)) {
            LocalDateTime since = LocalDateTime.now().minusMinutes(AGGREGATE_WINDOW_MINUTES);
            Optional<com.dataweave.master.companion.domain.PatrolReport> recent =
                    reportRepo.findRecentOpenAnomaly(run.tenantId(), run.projectId(), domain, since);
            if (recent.isPresent()) {
                reportRepo.incrementAggregate(recent.get().id());   // 聚合进既有卡片，不新建、不重复播报
                return;
            }
        }
        long reportId = reportRepo.insert(run.tenantId(), run.projectId(), run.id(), domain,
                severity, title, summary, detailJson, 1);
        com.dataweave.master.companion.domain.PatrolReport created = reportRepo.findById(reportId).orElseThrow();
        publisher.publish(run.projectId(), new CompanionEvent.ReportEvent("created", ReportView.from(created)));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
