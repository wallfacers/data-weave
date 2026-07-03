package com.dataweave.master.application.incident;

import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.domain.incident.Incident;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Incident 分诊计算服务（043）：爆炸半径 + 时间预算（research D7）。
 *
 * <p>在开单与 RESOLVED→OPEN 重开时各算一次；普通附着不重算。
 * 调用方：{@link IncidentService#openOrAttach} 开单后、重开 CAS 后。
 */
@Service
public class IncidentTriageService {

    private static final Logger log = LoggerFactory.getLogger(IncidentTriageService.class);

    private final LineageQueryService lineageQueryService;
    private final JdbcTemplate jdbc;

    public IncidentTriageService(LineageQueryService lineageQueryService, JdbcTemplate jdbc) {
        this.lineageQueryService = lineageQueryService;
        this.jdbc = jdbc;
    }

    /**
     * 为 TASK 类工单填充爆炸半径与时间预算。在 openOrAttach 开单 / 重开后调用。
     */
    public Incident enrichTriage(Incident inc) {
        if (inc == null) return inc;
        return switch (inc.getSourceKind()) {
            case "TASK" -> enrichTask(inc);
            case "NODE" -> inc; // NODE 工单无分诊
            case "WORKFLOW" -> enrichSlaWorkflow(inc); // SLA 工单
            default -> inc;
        };
    }

    // ── TASK 分诊：爆炸半径 + SLA 时间投影 ────────────────────────

    private Incident enrichTask(Incident inc) {
        long taskId;
        try {
            taskId = Long.parseLong(inc.getSourceRefId());
        } catch (NumberFormatException e) {
            return inc;
        }

        // ① 爆炸半径：下游任务数（neo4j BFS）
        inc.setBlastRadius(computeBlastRadius(inc.getTenantId(), inc.getProjectId(), taskId));

        // ② 时间预算：从 sla_baseline 投影
        inc.setTimeBudgetAt(computeTimeBudget(inc.getTenantId(), inc.getProjectId(), taskId));

        return inc;
    }

    /** 爆炸半径 = 下游任务数；neo4j 不可达 → NULL（区别于 0 = 无下游）。 */
    Integer computeBlastRadius(long tenantId, long projectId, long taskDefId) {
        try {
            LinkedHashMap<Long, Integer> downstream = lineageQueryService.downstreamTaskLevels(
                    tenantId, projectId, taskDefId);
            if (downstream == null) return null;
            // neo4j 不可达时被内部 catch 返回空 Map → 调用 canary 探测
            if (downstream.isEmpty()) {
                // 空结果：降级兜底 → 0（无下游）
                // 未来：包一层 neo4j 探活 → NULL 标记"血缘不可用"（research D7）
                return 0;
            }
            return downstream.size();
        } catch (Exception e) {
            log.warn("[Triage] blast radius unavailable; marking null. taskDefId={}", taskDefId, e);
            return null; // 不可达
        }
    }

    /**
     * 时间预算：从 sla_baseline 投影到当前 bizDate。
     *
     * <p>算法：查询该 task 所属 workflow 的最近 sla_baseline.baseline_ready_at，
     * 取其 time-of-day（HH:mm:ss），投影到今天的对应时刻。
     * 若该时刻已过（今天目标时刻 < now），则投影到明天。
     * 取所有相关 workflow 的最近将来时刻。
     */
    LocalDateTime computeTimeBudget(long tenantId, long projectId, long taskDefId) {
        try {
            // 找到该 task 所属的 workflow（从 workflow_node）
            List<Long> workflowIds = jdbc.query(
                    "SELECT DISTINCT wn.workflow_id FROM workflow_node wn " +
                    "JOIN workflow_def wd ON wd.id = wn.workflow_id AND wd.deleted = 0 " +
                    "WHERE wn.task_id = ? AND wn.node_type != 'VIRTUAL'",
                    (rs, n) -> rs.getLong("workflow_id"), taskDefId);

            // 取每个 workflow 的最近 baseline_ready_at
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime best = null;

            for (Long wfId : workflowIds) {
                List<LocalDateTime> baselines = jdbc.query(
                        "SELECT baseline_ready_at FROM sla_baseline " +
                        "WHERE workflow_id = ? AND tenant_id = ? AND deleted = 0 " +
                        "ORDER BY baseline_ready_at DESC LIMIT 3",
                        (rs, n) -> rs.getObject("baseline_ready_at", LocalDateTime.class),
                        wfId, tenantId);

                for (LocalDateTime baseline : baselines) {
                    if (baseline == null) continue;
                    // time-of-day 投影
                    LocalTime tod = baseline.toLocalTime();
                    LocalDateTime projected = tod.atDate(LocalDate.from(now));
                    if (projected.isBefore(now)) {
                        projected = tod.atDate(LocalDate.from(now).plusDays(1));
                    }
                    if (best == null || projected.isBefore(best)) {
                        best = projected;
                    }
                }
            }
            return best;
        } catch (Exception e) {
            log.warn("[Triage] time budget unavailable. taskDefId={}", taskDefId, e);
            return null;
        }
    }

    // ── SLA/WORKFLOW 工单 ───────────────────────────────────────

    /** SLA 工单的分诊：时间预算 = 破约时刻（已过去），前端显示"已超期"。 */
    private Incident enrichSlaWorkflow(Incident inc) {
        // blast_radius: SLA 工单也用下游数（从 workflow 反查其 task 来算），但通常 SLA 关注的是流程本身
        // time_budget: 从 sla_baseline 取 breach_minutes 算破约时刻
        try {
            long workflowId = Long.parseLong(inc.getSourceRefId());
            // 破约时刻：取最近的 breached=1 记录
            var row = jdbc.queryForMap(
                    "SELECT baseline_ready_at, breach_minutes FROM sla_baseline " +
                    "WHERE workflow_id = ? AND tenant_id = ? AND breached = 1 AND deleted = 0 " +
                    "ORDER BY baseline_ready_at DESC LIMIT 1",
                    workflowId, inc.getTenantId());
            if (row != null && !row.isEmpty()) {
                LocalDateTime baseline = (LocalDateTime) row.get("BASELINE_READY_AT");
                if (baseline != null) {
                    inc.setTimeBudgetAt(baseline); // 破约时刻，早于 now → 卡片显示"已超期"
                }
            }
        } catch (Exception e) {
            log.warn("[Triage] SLA triage failed for inc={}", inc.getId(), e);
        }
        inc.setBlastRadius(null); // SLA 工单爆炸半径不适用（按 workflow 维度）
        return inc;
    }
}
