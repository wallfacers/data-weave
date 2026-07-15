package com.dataweave.master.companion.application;

import java.util.List;

import com.dataweave.master.companion.domain.PatrolRoutine;
import com.dataweave.master.companion.domain.PatrolRun;
import com.dataweave.master.companion.domain.RunView;
import com.dataweave.master.companion.infrastructure.JdbcPatrolReportRepository;
import com.dataweave.master.companion.infrastructure.JdbcPatrolRoutineRepository;
import com.dataweave.master.companion.infrastructure.JdbcPatrolRunRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 巡检例程治理（T029 / US4）：列表 / PATCH（启停/调频/改范围）/ 执行历史。
 *
 * <p>PATCH 语义（契约）：字段<b>缺失</b>=不改；{@code scopeJson} <b>显式 null</b>=清空 scope。
 * 用 {@link JsonNode} 区分"缺失"与"null"（Java null 无法区分）。cron 变更校验 Spring CronExpression；
 * 变更落 {@code updated_by} 审计 + 乐观锁 {@code version}，刷新概况（下轮巡检时间联动）。
 */
@Service
public class RoutineService {

    private final JdbcPatrolRoutineRepository routineRepo;
    private final JdbcPatrolRunRepository runRepo;
    private final JdbcPatrolReportRepository reportRepo;
    private final CompanionBriefingService briefingService;

    public RoutineService(JdbcPatrolRoutineRepository routineRepo, JdbcPatrolRunRepository runRepo,
                          JdbcPatrolReportRepository reportRepo, CompanionBriefingService briefingService) {
        this.routineRepo = routineRepo;
        this.runRepo = runRepo;
        this.reportRepo = reportRepo;
        this.briefingService = briefingService;
    }

    public List<PatrolRoutine> list(long tenantId, long projectId) {
        return routineRepo.findByProject(tenantId, projectId);
    }

    /** PATCH 治理：缺失=不改；scopeJson 显式 null=清空；cron 变更校验；version 乐观锁。 */
    public PatrolRoutine patch(long tenantId, long projectId, long routineId, JsonNode patch, Long updatedBy) {
        PatrolRoutine r = require(tenantId, projectId, routineId);

        boolean enabled = patch.has("enabled") && patch.get("enabled").isBoolean()
                ? patch.get("enabled").asBoolean() : r.enabled();

        String cron = r.cronExpression();
        if (patch.has("cronExpression") && !patch.get("cronExpression").isNull()) {
            cron = patch.get("cronExpression").asString();
        }
        if (cron == null || cron.isBlank()) throw new BizException("companion.param_required", "cronExpression");
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new BizException("companion.routine_domain_unknown", cron);   // 复用：cron 非法
        }

        // scopeJson：缺失=不改；显式 null=清空(null)；有值=设值
        String scope = r.scopeJson();
        if (patch.has("scopeJson")) {
            scope = patch.get("scopeJson").isNull() ? null : patch.get("scopeJson").asString();
        }

        boolean ok = routineRepo.update(routineId, tenantId, projectId, enabled, cron, scope, updatedBy, r.version());
        if (!ok) throw new BizException("companion.routine_busy");   // version 不符（并发改动）
        briefingService.computeAndNotify(tenantId, projectId);        // 下轮巡检时间随 cron 联动
        return routineRepo.findById(routineId).orElseThrow();
    }

    /** 执行历史（触发时间/耗时/结论/关联汇报 id）。 */
    public List<RunView> runs(long tenantId, long projectId, long routineId, int limit) {
        require(tenantId, projectId, routineId);
        return runRepo.findByRoutine(routineId, limit).stream()
                .map(run -> RunView.from(run, reportIdsOf(run)))
                .toList();
    }

    private List<Long> reportIdsOf(PatrolRun run) {
        if (run == null) return List.of();
        return reportRepo.findByRun(run.id()).stream().map(r -> r.id()).toList();
    }

    private PatrolRoutine require(long tenantId, long projectId, long routineId) {
        return routineRepo.findById(routineId)
                .filter(r -> r.tenantId() == tenantId && r.projectId() == projectId)
                .orElseThrow(() -> new BizException("companion.routine_not_found", routineId));
    }
}
