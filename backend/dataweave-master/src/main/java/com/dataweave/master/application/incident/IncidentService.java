package com.dataweave.master.application.incident;

import com.dataweave.master.domain.incident.Incident;
import com.dataweave.master.domain.incident.IncidentEvent;
import com.dataweave.master.domain.incident.IncidentStates;
import com.dataweave.master.domain.signal.AlertSignal;
import com.dataweave.master.i18n.BizException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Incident 工单核心应用服务（043）。
 *
 * <p>公开契约方法 per tasks.md T005：
 * <ul>
 *   <li>{@link #openOrAttach(AlertSignal)} — 信号 → 开单/附着</li>
 *   <li>{@link #healByTask(long, long)} / {@link #healByWorkflowInstance(UUID, long)} — 自动愈合</li>
 *   <li>{@link #suppress}/{@link #unsuppress} / {@link #appendNote} — 处置</li>
 *   <li>{@link #appendTimeline} — 时间线追加（同事务 CAS 时内调）</li>
 *   <li>{@link #queue} / {@link #history} / {@link #detail} — 查询</li>
 *   <li>{@link #markMitigating} — CAS OPEN→MITIGATING</li>
 *   <li>{@link #findByAgentActionIncident} — 审批/重跑反查工单</li>
 * </ul>
 *
 * <h3>关键不变量</h3>
 * <ul>
 *   <li>附着优先写入（research D4）：先 UPDATE → 影响行数 0 才 INSERT；INSERT 撞 unique 重试附着</li>
 *   <li>状态全 CAS（{@code WHERE state=?}），多 master 安全</li>
 *   <li>STATE_CHANGE 时间线同事务写入</li>
 *   <li>diagnosis_json / proposal_json 恒 NULL（FR-013 槽位）</li>
 *   <li>timeline append-only，无 UPDATE/DELETE</li>
 * </ul>
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper json = new ObjectMapper();

    public IncidentService(JdbcTemplate jdbc, ApplicationEventPublisher eventPublisher) {
        this.jdbc = jdbc;
        this.eventPublisher = eventPublisher;
    }

    // ═══════════════════════════════════════════════════════════
    // §1 openOrAttach — 信号 → 开单 / 附着（核心写入路径）
    // ═══════════════════════════════════════════════════════════

    /**
     * 仅消费四类信号：TASK_FAILED / TASK_TIMEOUT / SLA_BREACH / NODE_OFFLINE。
     * 其余 type 直接 return null（由 listener 过滤，此处为防御）。
     */
    @Transactional
    public Incident openOrAttach(AlertSignal signal) {
        AlertSignal.Type type = signal.getType();
        Map<String, Object> ctx = signal.getContext();
        if (ctx == null) return null;

        return switch (type) {
            case TASK_FAILED, TASK_TIMEOUT -> openOrAttachTask(signal, ctx);
            case SLA_BREACH -> openOrAttachSla(signal, ctx);
            case NODE_OFFLINE -> openOrAttachNode(signal, ctx);
            default -> null; // 其余信号不接入（FR-001/METRIC_BREACH/QUALITY_FAILED/ASSET_CHANGED/WORKFLOW_STATE）
        };
    }

    // ── TASK ────────────────────────────────────────────────────

    private Incident openOrAttachTask(AlertSignal signal, Map<String, Object> ctx) {
        long tenantId = signal.getTenantId();
        Object tidRaw = ctx.get("taskId");
        long taskId = tidRaw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(tidRaw));
        String taskName = str(ctx, "taskName", "任务#" + taskId);
        String failureReason = str(ctx, "failureReason", "UNKNOWN");
        String signature = "T:" + taskId + ":" + failureReason;
        Object wiIdRaw = ctx.get("workflowInstanceId");
        UUID workflowInstanceId = wiIdRaw != null ? toUUID(wiIdRaw) : null;

        long projectId = resolveProjectId("task_instance",
                "id", toUUID(ctx.get("taskInstanceId")), tenantId);

        String title = taskName + " 失败(" + failureReason + ")";
        return openOrAttachInternal(tenantId, projectId, signature, title,
                signal.getSeverityHint(), "TASK", String.valueOf(taskId), taskName,
                workflowInstanceId, "TASK_SUCCESS", String.valueOf(taskId),
                signal, ctx);
    }

    // ── SLA ─────────────────────────────────────────────────────

    private Incident openOrAttachSla(AlertSignal signal, Map<String, Object> ctx) {
        long tenantId = signal.getTenantId();
        long workflowId = ((Number) ctx.get("workflowId")).longValue();
        String workflowName = str(ctx, "workflowName", "工作流#" + workflowId);
        UUID workflowInstanceId = toUUID(ctx.get("workflowInstanceId"));
        String signature = "WSLA:" + workflowId;

        long projectId = resolveProjectId("workflow_instance", "id", workflowInstanceId, tenantId);

        String title = workflowName + " SLA 破约";
        return openOrAttachInternal(tenantId, projectId, signature, title,
                signal.getSeverityHint(), "WORKFLOW", String.valueOf(workflowId), workflowName,
                workflowInstanceId, null, null,
                signal, ctx);
    }

    // ── NODE ────────────────────────────────────────────────────

    private Incident openOrAttachNode(AlertSignal signal, Map<String, Object> ctx) {
        long tenantId = signal.getTenantId();
        String nodeCode = str(ctx, "workerNodeCode", "?");
        String signature = "N:" + nodeCode + ":OFFLINE";

        Object tiRaw = ctx.get("taskInstanceId");
        long projectId = 1L; // 默认兜底
        if (tiRaw != null) {
            try {
                projectId = resolveProjectId("task_instance", "id", toUUID(tiRaw), tenantId);
            } catch (Exception e) {
                log.warn("[Incident] NODE_OFFLINE project resolve failed, using default: nodeCode={}", nodeCode, e);
            }
        }

        String title = nodeCode + " 节点离线";
        return openOrAttachInternal(tenantId, projectId, signature, title,
                signal.getSeverityHint(), "NODE", nodeCode, nodeCode,
                null, "NODE_ONLINE", nodeCode,
                signal, ctx);
    }

    // ── 核心附着/开单引擎（research D4：UPDATE 优先 → INSERT → 撞键重试）──

    private Incident openOrAttachInternal(long tenantId, long projectId,
                                           String signature, String title, String severityHint,
                                           String sourceKind, String sourceRefId, String sourceRefName,
                                           UUID workflowInstanceId,
                                           String healByType, String healByRefId,
                                           AlertSignal signal, Map<String, Object> ctx) {
        LocalDateTime now = LocalDateTime.now();
        String activeKey = signature;

        // 归并优先（FR-003）：同 workflowInstanceId 的未关闭工单
        if (workflowInstanceId != null) {
            Incident merged = tryMergeByWorkflowInstance(tenantId, workflowInstanceId,
                    severityHint, now, signal, ctx);
            if (merged != null) return merged;
        }

        // ① 附着：同 active_key 未关闭工单
        int updated = jdbc.update(
                "UPDATE incident SET occurrence_count = occurrence_count + 1, " +
                "last_seen_at = ?, severity = CASE WHEN ? > severity OR severity IS NULL THEN ? ELSE severity END, " +
                "state = CASE WHEN state = 'RESOLVED' THEN 'OPEN' ELSE state END, " +
                "updated_at = ? " +
                "WHERE active_key = ? AND tenant_id = ? AND project_id = ? AND deleted = 0",
                now, severityHint, severityHint, now,
                activeKey, tenantId, projectId);
        if (updated > 0) {
            Incident existing = loadByActiveKey(tenantId, projectId, activeKey);
            if (existing != null) {
                appendTimelineInternal(existing.getId(), tenantId, "SIGNAL",
                        toJson(signalContextMap(signal, ctx)), "system", now);
                // 复发重开 → STATE_CHANGE
                if ("RESOLVED".equals(existing.getState()) && updated > 0) {
                    // 状态已被 UPDATE 改为 OPEN，记录 STATE_CHANGE
                    String payload = toJson(Map.of("from", "RESOLVED", "to", "OPEN", "reason", "复发"));
                    appendTimelineInternal(existing.getId(), tenantId, "STATE_CHANGE", payload, "system", now);
                }
                return existing;
            }
        }

        // ② 开单：INSERT（2 次重试防竞态）
        int maxRetries = 2;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Long id = insertIncident(tenantId, projectId, signature, activeKey, title,
                        severityHint, sourceKind, sourceRefId, sourceRefName,
                        workflowInstanceId, healByType, healByRefId, now);
                if (id != null) {
                    appendTimelineInternal(id, tenantId, "SIGNAL",
                            toJson(signalContextMap(signal, ctx)), "system", now);
                    appendTimelineInternal(id, tenantId, "STATE_CHANGE",
                            toJson(Map.of("from", null, "to", "OPEN", "reason", "开单")), "system", now);
                    return loadById(id);
                }
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    // 撞 active_key UNIQUE → 重试附着
                    int retryUpdated = jdbc.update(
                            "UPDATE incident SET occurrence_count = occurrence_count + 1, " +
                            "last_seen_at = ?, severity = CASE WHEN ? > severity OR severity IS NULL THEN ? ELSE severity END, " +
                            "state = CASE WHEN state = 'RESOLVED' THEN 'OPEN' ELSE state END, " +
                            "updated_at = ? " +
                            "WHERE active_key = ? AND tenant_id = ? AND project_id = ? AND deleted = 0",
                            now, severityHint, severityHint, now,
                            activeKey, tenantId, projectId);
                    if (retryUpdated > 0) {
                        Incident existing = loadByActiveKey(tenantId, projectId, activeKey);
                        if (existing != null) {
                            appendTimelineInternal(existing.getId(), tenantId, "SIGNAL",
                                    toJson(signalContextMap(signal, ctx)), "system", now);
                            return existing;
                        }
                    }
                } else {
                    log.error("[Incident] openOrAttach failed after retries: sig={}", signature, e);
                    return null;
                }
            }
        }
        return null;
    }

    // ── INSERT 辅助 ──────────────────────────────────────────────

    private Long insertIncident(long tenantId, long projectId, String signature, String activeKey,
                                 String title, String severity, String sourceKind, String sourceRefId,
                                 String sourceRefName, UUID workflowInstanceId,
                                 String healByType, String healByRefId, LocalDateTime now) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident (tenant_id, project_id, signature, active_key, title, " +
                    "severity, state, source_kind, source_ref_id, source_ref_name, workflow_instance_id, " +
                    "heal_by_type, heal_by_ref_id, " +
                    "occurrence_count, first_seen_at, last_seen_at, " +
                    "created_by, updated_by, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            int i = 0;
            ps.setLong(++i, tenantId);
            ps.setLong(++i, projectId);
            ps.setString(++i, signature);
            ps.setString(++i, activeKey);
            ps.setString(++i, title);
            ps.setString(++i, severity);
            ps.setString(++i, IncidentStates.OPEN);
            ps.setString(++i, sourceKind);
            ps.setString(++i, sourceRefId);
            ps.setString(++i, sourceRefName);
            ps.setObject(++i, workflowInstanceId);
            ps.setString(++i, healByType);
            ps.setString(++i, healByRefId);
            ps.setInt(++i, 1);
            ps.setObject(++i, now);
            ps.setObject(++i, now);
            ps.setLong(++i, 1L);  // created_by = system (admin)
            ps.setLong(++i, 1L);
            ps.setObject(++i, now);
            ps.setObject(++i, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : null;
    }

    // ── 同工作流实例归并（FR-003）──────────────────────────────────

    private Incident tryMergeByWorkflowInstance(long tenantId, UUID workflowInstanceId,
                                                 String severityHint, LocalDateTime now,
                                                 AlertSignal signal, Map<String, Object> ctx) {
        int updated = jdbc.update(
                "UPDATE incident SET occurrence_count = occurrence_count + 1, " +
                "last_seen_at = ?, severity = CASE WHEN ? > severity OR severity IS NULL THEN ? ELSE severity END, " +
                "state = CASE WHEN state = 'RESOLVED' THEN 'OPEN' ELSE state END, " +
                "updated_at = ? " +
                "WHERE workflow_instance_id = ? AND tenant_id = ? AND state != 'CLOSED' AND deleted = 0",
                now, severityHint, severityHint, now,
                workflowInstanceId, tenantId);
        if (updated > 0) {
            var list = jdbc.query(
                    "SELECT * FROM incident WHERE workflow_instance_id = ? AND tenant_id = ? AND deleted = 0",
                    (rs, n) -> mapIncident(rs), workflowInstanceId, tenantId);
            Incident inc = list.isEmpty() ? null : list.get(0);
            if (inc != null) {
                appendTimelineInternal(inc.getId(), tenantId, "SIGNAL",
                        toJson(signalContextMap(signal, ctx)), "system", now);
            }
            return inc;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    // §2 自动愈合（被 listener 调用）
    // ═══════════════════════════════════════════════════════════

    /**
     * 按愈合条件精确匹配：healByType + healByRefId → CAS 到 RESOLVED（064 精确指纹愈合）。
     * 仅在 signal 到达前仍处于 OPEN/MITIGATING 时 CAS。
     */
    public void healByTask(String healByType, String healByRefId, long tenantId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update(
                "UPDATE incident SET state = 'RESOLVED', resolved_at = ?, resolution_kind = 'AUTO_HEAL', " +
                "updated_at = ? " +
                "WHERE tenant_id = ? AND state IN ('OPEN','MITIGATING') " +
                "AND heal_by_type = ? AND heal_by_ref_id = ? AND deleted = 0",
                now, now, tenantId, healByType, healByRefId);
        if (updated > 0) {
            var healed = jdbc.query(
                    "SELECT id FROM incident WHERE tenant_id = ? " +
                    "AND heal_by_type = ? AND heal_by_ref_id = ? AND state = 'RESOLVED' AND deleted = 0",
                    (rs, n) -> rs.getLong(1), tenantId, healByType, healByRefId);
            for (Long id : healed) {
                String payload = toJson(Map.of(
                        "from", "OPEN/MITIGATING", "to", "RESOLVED", "reason", "自动愈合：任务恢复成功"));
                appendTimelineInternal(id, tenantId, "STATE_CHANGE", payload, "system", now);
            }
        }
    }

    /**
     * 按 workflowInstanceId 愈合：找到该实例未关闭的 WORKFLOW/SLA 类工单 → CAS 到 RESOLVED。
     */
    public void healByWorkflowInstance(UUID workflowInstanceId, long tenantId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update(
                "UPDATE incident SET state = 'RESOLVED', resolved_at = ?, resolution_kind = 'AUTO_HEAL', " +
                "updated_at = ? " +
                "WHERE workflow_instance_id = ? AND tenant_id = ? AND state IN ('OPEN','MITIGATING') " +
                "AND deleted = 0",
                now, now, workflowInstanceId, tenantId);
        if (updated > 0) {
            var healed = jdbc.query(
                    "SELECT id FROM incident WHERE workflow_instance_id = ? AND tenant_id = ? " +
                    "AND state = 'RESOLVED' AND deleted = 0",
                    (rs, n) -> rs.getLong(1), workflowInstanceId, tenantId);
            for (Long id : healed) {
                String payload = toJson(Map.of(
                        "from", "OPEN/MITIGATING", "to", "RESOLVED", "reason", "自动愈合：工作流恢复成功"));
                appendTimelineInternal(id, tenantId, "STATE_CHANGE", payload, "system", now);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // §3 suppress / unsuppress / appendNote / markMitigating
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void suppress(long id, String reason, String actor) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update(
                "UPDATE incident SET state = 'SUPPRESSED', suppress_reason = ?, updated_at = ? " +
                "WHERE id = ? AND state IN ('OPEN','MITIGATING') AND deleted = 0",
                reason, now, id);
        if (updated == 0) {
            Incident inc = loadById(id);
            if (inc == null) throw new BizException("incident.not_found", id);
            throw new BizException("incident.invalid_state", inc.getState()).withHttpStatus(409);
        }
        String payload = toJson(Map.of("from", "OPEN/MITIGATING", "to", "SUPPRESSED", "reason", reason));
        Incident inc = loadById(id);
        appendTimelineInternal(id, inc != null ? inc.getTenantId() : 1L, "STATE_CHANGE", payload, actor, now);
    }

    @Transactional
    public void unsuppress(long id, String actor) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update(
                "UPDATE incident SET state = 'OPEN', suppress_reason = NULL, updated_at = ? " +
                "WHERE id = ? AND state = 'SUPPRESSED' AND deleted = 0",
                now, id);
        if (updated == 0) {
            Incident inc = loadById(id);
            if (inc == null) throw new BizException("incident.not_found", id);
            throw new BizException("incident.invalid_state", inc.getState()).withHttpStatus(409);
        }
        String payload = toJson(Map.of("from", "SUPPRESSED", "to", "OPEN", "reason", "取消静默"));
        Incident inc = loadById(id);
        appendTimelineInternal(id, inc != null ? inc.getTenantId() : 1L, "STATE_CHANGE", payload, actor, now);
    }

    @Transactional
    public void appendNote(long id, String text, String actor) {
        Incident inc = loadById(id);
        if (inc == null) throw new BizException("incident.not_found", id);
        if (IncidentStates.isTerminal(inc.getState()))
            throw new BizException("incident.invalid_state", inc.getState()).withHttpStatus(409);
        appendTimelineInternal(id, inc.getTenantId(), "NOTE", toJson(Map.of("text", text)), actor, LocalDateTime.now());
    }

    @Transactional
    public void markMitigating(long id) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update(
                "UPDATE incident SET state = 'MITIGATING', updated_at = ? " +
                "WHERE id = ? AND state = 'OPEN' AND deleted = 0",
                now, id);
        if (updated > 0) {
            Incident inc = loadById(id);
            String payload = toJson(Map.of("from", "OPEN", "to", "MITIGATING", "reason", "处置动作已提交"));
            appendTimelineInternal(id, inc != null ? inc.getTenantId() : 1L, "STATE_CHANGE", payload, "system", now);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // §4 appendTimeline（公开，供 ApprovalService/Controller 回挂）
    // ═══════════════════════════════════════════════════════════

    public void appendTimeline(long incidentId, String kind, String payloadJson, String actor) {
        Incident inc = loadById(incidentId);
        if (inc == null) return;
        appendTimelineInternal(incidentId, inc.getTenantId(), kind, payloadJson, actor, LocalDateTime.now());
    }

    private void appendTimelineInternal(long incidentId, long tenantId, String kind,
                                         String payloadJson, String actor, LocalDateTime now) {
        Integer maxSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM incident_event WHERE incident_id = ?",
                Integer.class, incidentId);
        int seq = (maxSeq != null ? maxSeq : 0) + 1;
        jdbc.update("INSERT INTO incident_event (tenant_id, incident_id, seq, kind, payload_json, actor, created_at) " +
                    "VALUES (?,?,?,?,?,?,?)",
                tenantId, incidentId, seq, kind, payloadJson, actor, now);
    }

    // ═══════════════════════════════════════════════════════════
    // §5 查询：queue / history / detail
    // ═══════════════════════════════════════════════════════════

    /**
     * 三区队列（默认视图）：active（OPEN+MITIGATING，按紧迫度排序）+ recentResolved（24h内 RESOLVED，降序）。
     * 量级小（十/百），服务端 Java 内存排序（research D8）。
     * pendingActionCount / priorIncidentCount 富化留到 T016。
     */
    public Map<String, Object> queue(long tenantId, long projectId) {
        List<Incident> active = jdbc.query(
                "SELECT * FROM incident WHERE tenant_id = ? AND project_id = ? " +
                "AND state IN ('OPEN','MITIGATING') AND deleted = 0",
                (rs, n) -> mapIncident(rs), tenantId, projectId);
        List<Incident> recentResolved = jdbc.query(
                "SELECT * FROM incident WHERE tenant_id = ? AND project_id = ? " +
                "AND state = 'RESOLVED' AND resolved_at >= ? AND deleted = 0",
                (rs, n) -> mapIncident(rs), tenantId, projectId,
                LocalDateTime.now().minusHours(24));

        // D8 紧迫度排序（active 区）：① 有 time_budget_at 且未过期按剩余时间升序 ② 已过期按超期降序
        //   ③ 无 timeBudget 按 blast_radius 降序 ④ 按 severity rank ⑤ 按 last_seen_at 降序
        LocalDateTime now = LocalDateTime.now();
        active.sort((a, b) -> {
            int cmp = compareUrgency(a, b, now);
            if (cmp != 0) return cmp;
            return b.getLastSeenAt().compareTo(a.getLastSeenAt()); // 并列按最近发生时间
        });

        recentResolved.sort((a, b) -> b.getResolvedAt().compareTo(a.getResolvedAt()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", active);
        result.put("recentResolved", recentResolved);
        result.put("activeCount", active.size());
        result.put("recentResolvedCount", recentResolved.size());
        return result;
    }

    /** D8 紧迫度比较：① 未过期升序 ② 已过期降序 ③ blast_radius 降序 ④ severity rank */
    private int compareUrgency(Incident a, Incident b, LocalDateTime now) {
        // 有 timeBudget 的优先
        boolean aHasBudget = a.getTimeBudgetAt() != null;
        boolean bHasBudget = b.getTimeBudgetAt() != null;
        if (aHasBudget && !bHasBudget) return -1;
        if (!aHasBudget && bHasBudget) return 1;

        if (aHasBudget) {
            boolean aOverdue = a.getTimeBudgetAt().isBefore(now);
            boolean bOverdue = b.getTimeBudgetAt().isBefore(now);
            if (aOverdue && !bOverdue) return 1;
            if (!aOverdue && bOverdue) return -1;
            if (!aOverdue) {
                // 均未过期：剩余时间短的优先
                return a.getTimeBudgetAt().compareTo(b.getTimeBudgetAt());
            } else {
                // 均已过期：超期久的优先（晚的时间更早过期）
                return b.getTimeBudgetAt().compareTo(a.getTimeBudgetAt());
            }
        }

        // 无 timeBudget：blast_radius 降序
        int aBlast = a.getBlastRadius() != null ? a.getBlastRadius() : -1;
        int bBlast = b.getBlastRadius() != null ? b.getBlastRadius() : -1;
        return Integer.compare(bBlast, aBlast);
    }

    /** 历史筛选分页 */
    public Map<String, Object> history(long tenantId, long projectId,
                                        String state, String signature,
                                        LocalDateTime from, LocalDateTime to,
                                        int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM incident WHERE tenant_id = ? AND project_id = ? AND deleted = 0");
        List<Object> params = new ArrayList<>(List.of(tenantId, projectId));

        if (state != null && !state.isBlank()) {
            sql.append(" AND state = ?");
            params.add(state);
        }
        if (signature != null && !signature.isBlank()) {
            sql.append(" AND signature = ?");
            params.add(signature);
        }
        if (from != null) {
            sql.append(" AND last_seen_at >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND last_seen_at < ?");
            params.add(to);
        }

        // total count
        String countSql = sql.toString().replace("SELECT *", "SELECT COUNT(*)");
        long total = jdbc.queryForObject(countSql, Long.class, params.toArray());

        sql.append(" ORDER BY last_seen_at DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add((page - 1) * size);

        List<Incident> items = jdbc.query(sql.toString(), (rs, n) -> mapIncident(rs), params.toArray());

        return Map.of("items", items, "total", total);
    }

    /** 详情 + timeline + actions */
    public Map<String, Object> detail(long id) {
        Incident inc = loadById(id);
        if (inc == null) throw new BizException("incident.not_found", id);

        List<IncidentEvent> timeline = jdbc.query(
                "SELECT * FROM incident_event WHERE incident_id = ? ORDER BY seq ASC",
                (rs, n) -> mapIncidentEvent(rs), id);

        List<Map<String, Object>> actions = jdbc.query(
                "SELECT id, action_type, approval_status, summary, policy_level, executed_at, result_json " +
                "FROM agent_action WHERE incident_id = ? ORDER BY id DESC",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("actionType", rs.getString("action_type"));
                    m.put("approvalStatus", rs.getString("approval_status"));
                    m.put("summary", rs.getString("summary"));
                    m.put("policyLevel", rs.getString("policy_level"));
                    m.put("executedAt", rs.getObject("executed_at"));
                    m.put("resultJson", rs.getString("result_json"));
                    return m;
                }, id);

        return Map.of("incident", inc, "timeline", timeline, "actions", actions);
    }

    // ═══════════════════════════════════════════════════════════
    // §6 findByAgentActionIncident
    // ═══════════════════════════════════════════════════════════

    public Incident findByAgentActionIncident(long actionId) {
        var list = jdbc.query(
                "SELECT i.* FROM incident i JOIN agent_action a ON a.incident_id = i.id WHERE a.id = ?",
                (rs, n) -> mapIncident(rs), actionId);
        return list.isEmpty() ? null : list.get(0);
    }

    // ═══════════════════════════════════════════════════════════
    // §7 清扫操作（被 sweeper / heal listener 调用）
    // ═══════════════════════════════════════════════════════════

    /** RESOLVED 超 7 天 → 集合 CAS 转 CLOSED（D6，幂等，多 master 安全）。返回关闭条数。 */
    public int sweepStaleResolved() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        int updated = jdbc.update(
                "UPDATE incident SET state = 'CLOSED', active_key = NULL, closed_at = ?, updated_at = ? " +
                "WHERE state = 'RESOLVED' AND resolved_at < ? AND deleted = 0",
                LocalDateTime.now(), LocalDateTime.now(), cutoff);
        if (updated > 0) {
            log.info("[Incident] sweeper closed {} stale RESOLVED incidents (before {})", updated, cutoff);
        }
        return updated;
    }

    /** NODE 类工单：nodeCode 心跳恢复 → CAS RESOLVED。返回愈合条数。 */
    public int healNodesByCode(String nodeCode, long tenantId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update(
                "UPDATE incident SET state = 'RESOLVED', resolved_at = ?, resolution_kind = 'AUTO_HEAL', " +
                "updated_at = ? " +
                "WHERE tenant_id = ? AND source_kind = 'NODE' AND source_ref_id = ? " +
                "AND state IN ('OPEN','MITIGATING') AND deleted = 0",
                now, now, tenantId, nodeCode);
        if (updated > 0) {
            var healed = jdbc.query(
                    "SELECT id FROM incident WHERE tenant_id = ? AND source_kind = 'NODE' " +
                    "AND source_ref_id = ? AND state = 'RESOLVED' AND deleted = 0",
                    (rs, n) -> rs.getLong(1), tenantId, nodeCode);
            for (Long id : healed) {
                String payload = toJson(Map.of(
                        "from", "OPEN/MITIGATING", "to", "RESOLVED", "reason", "自动愈合：节点心跳恢复"));
                appendTimelineInternal(id, tenantId, "STATE_CHANGE", payload, "system", now);
            }
        }
        return updated;
    }

    // ═══════════════════════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════════════════════

    Incident loadById(long id) {
        var list = jdbc.query("SELECT * FROM incident WHERE id = ? AND deleted = 0",
                (rs, n) -> mapIncident(rs), id);
        return list.isEmpty() ? null : list.get(0);
    }

    private Incident loadByActiveKey(long tenantId, long projectId, String activeKey) {
        var list = jdbc.query(
                "SELECT * FROM incident WHERE active_key = ? AND tenant_id = ? AND project_id = ? AND deleted = 0",
                (rs, n) -> mapIncident(rs), activeKey, tenantId, projectId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 从实体表解析 projectId（task_instance / workflow_instance）。 */
    private long resolveProjectId(String table, String idCol, UUID uuid, long tenantId) {
        if (uuid == null) return 1L;
        try {
            Long pid = jdbc.queryForObject(
                    "SELECT project_id FROM " + table + " WHERE " + idCol + " = ? AND tenant_id = ?",
                    Long.class, uuid, tenantId);
            return pid != null ? pid : 1L;
        } catch (Exception e) {
            return 1L; // 兜底
        }
    }

    /** failureReason 归一：TIMEOUT / EXIT_NONZERO / WORKER_RESTART / WORKER_LOST / UNKNOWN */
    static String normalizeFailureClass(String reason) {
        if (reason == null) return "UNKNOWN";
        String upper = reason.toUpperCase();
        if (upper.contains("TIMEOUT")) return "TIMEOUT";
        if (upper.contains("EXIT") || upper.contains("NONZERO")) return "EXIT_NONZERO";
        if (upper.contains("WORKER_RESTART") || upper.contains("RESTART")) return "WORKER_RESTART";
        if (upper.contains("WORKER_LOST") || upper.contains("LOST")) return "WORKER_LOST";
        return "UNKNOWN";
    }

    // ── Row mapping ──────────────────────────────────────────────

    Incident mapIncident(java.sql.ResultSet rs) throws java.sql.SQLException {
        Incident inc = new Incident();
        inc.setId(rs.getLong("id"));
        inc.setTenantId(rs.getLong("tenant_id"));
        inc.setProjectId(rs.getLong("project_id"));
        inc.setSignature(rs.getString("signature"));
        inc.setActiveKey(rs.getString("active_key"));
        inc.setTitle(rs.getString("title"));
        inc.setSeverity(rs.getString("severity"));
        inc.setState(rs.getString("state"));
        inc.setSourceKind(rs.getString("source_kind"));
        inc.setSourceRefId(rs.getString("source_ref_id"));
        inc.setSourceRefName(rs.getString("source_ref_name"));
        inc.setWorkflowInstanceId(rs.getObject("workflow_instance_id", UUID.class));
        inc.setOccurrenceCount(rs.getInt("occurrence_count"));
        inc.setFirstSeenAt(rs.getObject("first_seen_at", LocalDateTime.class));
        inc.setLastSeenAt(rs.getObject("last_seen_at", LocalDateTime.class));
        inc.setBlastRadius(rs.getObject("blast_radius") != null ? rs.getInt("blast_radius") : null);
        inc.setTimeBudgetAt(rs.getObject("time_budget_at", LocalDateTime.class));
        inc.setSuppressReason(rs.getString("suppress_reason"));
        inc.setResolutionKind(rs.getString("resolution_kind"));
        inc.setHealByType(rs.getString("heal_by_type"));
        inc.setHealByRefId(rs.getString("heal_by_ref_id"));
        inc.setResolvedAt(rs.getObject("resolved_at", LocalDateTime.class));
        inc.setClosedAt(rs.getObject("closed_at", LocalDateTime.class));
        inc.setDiagnosisJson(rs.getString("diagnosis_json"));
        inc.setProposalJson(rs.getString("proposal_json"));
        inc.setCreatedBy(rs.getObject("created_by") != null ? rs.getLong("created_by") : null);
        inc.setUpdatedBy(rs.getObject("updated_by") != null ? rs.getLong("updated_by") : null);
        inc.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        inc.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
        inc.setDeleted(rs.getInt("deleted"));
        inc.setVersion(rs.getInt("version"));
        return inc;
    }

    IncidentEvent mapIncidentEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        IncidentEvent e = new IncidentEvent();
        e.setId(rs.getLong("id"));
        e.setTenantId(rs.getLong("tenant_id"));
        e.setIncidentId(rs.getLong("incident_id"));
        e.setSeq(rs.getInt("seq"));
        e.setKind(rs.getString("kind"));
        e.setPayloadJson(rs.getString("payload_json"));
        e.setActor(rs.getString("actor"));
        e.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        return e;
    }

    // ── JSON / UUID helpers ──────────────────────────────────────

    public String toJson(Object obj) {
        try { return json.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }

    Map<String, Object> fromJson(String jsonStr) {
        try { return json.readValue(jsonStr, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    static UUID toUUID(Object raw) {
        if (raw == null) return null;
        if (raw instanceof UUID u) return u;
        try { return UUID.fromString(raw.toString()); } catch (Exception e) { return null; }
    }

    static String str(Map<String, Object> ctx, String key, String defaultVal) {
        Object v = ctx.get(key);
        return v != null ? v.toString() : defaultVal;
    }

    /** 信号上下文字典（供 timeline payload 存 SIGNAL 摘要）。 */
    static Map<String, Object> signalContextMap(AlertSignal signal, Map<String, Object> ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", signal.getType().name());
        m.put("tenantId", signal.getTenantId());
        m.put("severity", signal.getSeverityHint());
        m.put("occurredAt", signal.getOccurredAt().toString());
        m.putAll(ctx);
        return m;
    }
}
