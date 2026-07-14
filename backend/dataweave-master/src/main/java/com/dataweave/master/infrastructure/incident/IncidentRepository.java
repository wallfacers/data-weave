package com.dataweave.master.infrastructure.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.dataweave.master.domain.Uuid7;
import com.dataweave.master.domain.incident.Incident;
import com.dataweave.master.domain.incident.IncidentStates;
import com.dataweave.master.domain.incident.IncidentStats;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * incident 表 JdbcTemplate 仓储。开单单赢靠 {@code UNIQUE(tenant_id, open_key)}——
 * 开着时 open_key=task_def_id，收口置 NULL（ANSI NULL-distinct 语义使收口后可重新开单，免 partial index）。
 * 全部状态推进走乐观 CAS（{@code WHERE state=?}），守 060/067 调度锁序与内核零侵入红线。
 */
@Repository
public class IncidentRepository {

    private final JdbcTemplate jdbc;

    public IncidentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 单赢开单：唯一约束冲突即视为已开单（并发多 sweeper 防重），返回 empty 交调用方走归并路径。 */
    public Optional<Incident> tryOpen(long tenantId, long projectId, long taskDefId, String taskDefName,
                                       UUID instanceId, String triggerSource) {
        UUID id = Uuid7.generate();
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbc.update(
                    "INSERT INTO incident (id, tenant_id, project_id, task_def_id, task_def_name, " +
                    "  first_instance_id, latest_instance_id, instance_count, trigger_source, state, " +
                    "  open_key, auto_action_count, opened_at, version, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?,1,?,?,?,0,?,0,?,?)",
                    id, tenantId, projectId, taskDefId, taskDefName, instanceId, instanceId,
                    triggerSource, IncidentStates.OPEN, taskDefId, now, now, now);
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
        linkInstance(id, instanceId);
        return findById(id);
    }

    /**
     * 事故-实例幂等关联：insert 冲突（同一实例已关联过此事故）即视为已处理，返回 false。
     * 是巡检器重复扫描同一失败实例时避免误重复归并的正确性地基（PK(incident_id, instance_id) 天然幂等）。
     */
    public boolean linkInstance(UUID incidentId, UUID instanceId) {
        try {
            jdbc.update("INSERT INTO incident_instance (incident_id, instance_id, created_at) VALUES (?,?,?)",
                    incidentId, instanceId, LocalDateTime.now());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 归并：同任务后续失败追加进既有未收口事故（latest_instance_id 更新 + instance_count++）。 */
    public int mergeInstance(long tenantId, long taskDefId, UUID instanceId) {
        return jdbc.update(
                "UPDATE incident SET latest_instance_id = ?, instance_count = instance_count + 1, " +
                "  updated_at = ? WHERE tenant_id = ? AND open_key = ?",
                instanceId, LocalDateTime.now(), tenantId, taskDefId);
    }

    public Optional<Incident> findOpenByTask(long tenantId, long taskDefId) {
        List<Incident> list = jdbc.query(
                "SELECT * FROM incident WHERE tenant_id = ? AND open_key = ?",
                (rs, n) -> map(rs), tenantId, taskDefId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<Incident> findById(UUID id) {
        List<Incident> list = jdbc.query("SELECT * FROM incident WHERE id = ?", (rs, n) -> map(rs), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 分页列表：待处理（AWAITING_APPROVAL/NEEDS_HUMAN）置顶，其余按开单时间倒序。 */
    public List<Incident> findPage(long tenantId, long projectId, List<String> states, Long taskDefId,
                                    int offset, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM incident WHERE tenant_id = ? AND project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(projectId);
        if (states != null && !states.isEmpty()) {
            sql.append(" AND state IN (")
                    .append(String.join(",", states.stream().map(s -> "?").toList()))
                    .append(")");
            args.addAll(states);
        }
        if (taskDefId != null) {
            sql.append(" AND task_def_id = ?");
            args.add(taskDefId);
        }
        sql.append(" ORDER BY CASE WHEN state IN ('AWAITING_APPROVAL','NEEDS_HUMAN') THEN 0 ELSE 1 END, " +
                "opened_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> map(rs), args.toArray());
    }

    /** 巡检扫描用：未收口事故（任意状态非终态）。 */
    public List<Incident> findAllOpen(long tenantId) {
        return jdbc.query(
                "SELECT * FROM incident WHERE tenant_id = ? AND open_key IS NOT NULL",
                (rs, n) -> map(rs), tenantId);
    }

    /** 指挥中心快照用：项目内全部未收口事故，待处理（AWAITING_APPROVAL/NEEDS_HUMAN）置顶（同 findPage 排序）。 */
    public List<Incident> findOpenByProject(long tenantId, long projectId) {
        return jdbc.query(
                "SELECT * FROM incident WHERE tenant_id = ? AND project_id = ? AND open_key IS NOT NULL " +
                "ORDER BY CASE WHEN state IN ('AWAITING_APPROVAL','NEEDS_HUMAN') THEN 0 ELSE 1 END, opened_at DESC",
                (rs, n) -> map(rs), tenantId, projectId);
    }

    /** 跨租户扫描（后台巡检用，同 TimeoutSweeper/StuckInstanceSweeper 全局扫描惯例）：按状态取全部事故。 */
    public List<Incident> findAllByState(String state) {
        return jdbc.query("SELECT * FROM incident WHERE state = ?", (rs, n) -> map(rs), state);
    }

    /**
     * 指挥中心实时数字（SC-010）：单次聚合按状态计数，永远直算 incident 表当下事实。
     * resolvedToday 以 closed_at ≥ 今日零点为界（调用方传今日零点 timestamp，避免仓储层依赖时钟语义）。
     */
    public IncidentStats stats(long tenantId, long projectId, LocalDateTime todayStart) {
        int active = count("open_key IS NOT NULL", tenantId, projectId);
        int agentWorking = count("state IN ('OPEN','ANALYZING','ACTING')", tenantId, projectId);
        int awaitingApproval = count("state = 'AWAITING_APPROVAL'", tenantId, projectId);
        int needsHuman = count("state = 'NEEDS_HUMAN'", tenantId, projectId);
        Integer resolvedToday = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident WHERE tenant_id = ? AND project_id = ? " +
                "AND state = 'RESOLVED' AND closed_at >= ?",
                Integer.class, tenantId, projectId, todayStart);
        return new IncidentStats(active, agentWorking, awaitingApproval, needsHuman,
                resolvedToday == null ? 0 : resolvedToday);
    }

    private int count(String predicate, long tenantId, long projectId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident WHERE tenant_id = ? AND project_id = ? AND " + predicate,
                Integer.class, tenantId, projectId);
        return c == null ? 0 : c;
    }

    public int countInflight(long tenantId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident WHERE tenant_id = ? AND state IN ('OPEN','ANALYZING','ACTING')",
                Integer.class, tenantId);
        return c == null ? 0 : c;
    }

    /** 乐观 CAS 推进状态（不涉及收口）。 */
    public boolean casState(UUID id, String from, String to) {
        int rows = jdbc.update(
                "UPDATE incident SET state = ?, updated_at = ?, version = version + 1 WHERE id = ? AND state = ?",
                to, LocalDateTime.now(), id, from);
        return rows == 1;
    }

    /**
     * 067 T026：CAS 状态推进 + 同时预置收口口径提示位（{@code close_kind}）。人工标记已处理/显式复验
     * 触发的 NEEDS_HUMAN→ACTING 转移专用——通用 {@code actOrVerify} 循环日后 SUCCESS 收口时据此判定
     * AUTO/HUMAN_ASSISTED（未收口前 close_kind 非空只是「提示位」，不代表已收口，见 escalate 清位对偶）。
     */
    public boolean casStateWithCloseKindHint(UUID id, String from, String to, String closeKindHint) {
        int rows = jdbc.update(
                "UPDATE incident SET state = ?, close_kind = ?, updated_at = ?, version = version + 1 " +
                "WHERE id = ? AND state = ?",
                to, closeKindHint, LocalDateTime.now(), id, from);
        return rows == 1;
    }

    /** 清除收口口径提示位（escalate 再次转人工时对偶操作，防止提示位残留误标下一次收口）。 */
    public boolean clearCloseKindHint(UUID id) {
        int rows = jdbc.update("UPDATE incident SET close_kind = NULL, updated_at = ? WHERE id = ?",
                LocalDateTime.now(), id);
        return rows == 1;
    }

    /** 落诊断结论（分型/置信度/摘要/建议），同时 CAS 状态。 */
    public boolean applyDiagnosis(UUID id, String from, String to, String classification, String confidence,
                                   String summary, String suggestion) {
        int rows = jdbc.update(
                "UPDATE incident SET state = ?, classification = ?, confidence = ?, summary = ?, " +
                "  suggestion = ?, updated_at = ?, version = version + 1 WHERE id = ? AND state = ?",
                to, classification, confidence, summary, suggestion, LocalDateTime.now(), id, from);
        return rows == 1;
    }

    /** 防循环计数（只增不减）。 */
    public boolean incrementAutoAction(UUID id) {
        int rows = jdbc.update(
                "UPDATE incident SET auto_action_count = auto_action_count + 1, updated_at = ? WHERE id = ?",
                LocalDateTime.now(), id);
        return rows == 1;
    }

    /** 收口：state→RESOLVED，open_key 置 NULL 释放唯一约束（收口后可重新开单）。 */
    public boolean casClose(UUID id, String closeKind) {
        LocalDateTime now = LocalDateTime.now();
        int rows = jdbc.update(
                "UPDATE incident SET state = ?, open_key = NULL, close_kind = ?, closed_at = ?, " +
                "  updated_at = ?, version = version + 1 WHERE id = ? AND state <> ?",
                IncidentStates.RESOLVED, closeKind, now, now, id, IncidentStates.RESOLVED);
        return rows == 1;
    }

    private Incident map(ResultSet rs) throws SQLException {
        return new Incident(
                (UUID) rs.getObject("id"),
                rs.getLong("tenant_id"),
                rs.getLong("project_id"),
                rs.getLong("task_def_id"),
                rs.getString("task_def_name"),
                (UUID) rs.getObject("first_instance_id"),
                (UUID) rs.getObject("latest_instance_id"),
                rs.getInt("instance_count"),
                rs.getString("trigger_source"),
                rs.getString("classification"),
                rs.getString("confidence"),
                rs.getString("state"),
                rs.getObject("open_key", Long.class),
                rs.getInt("auto_action_count"),
                rs.getString("summary"),
                rs.getString("suggestion"),
                rs.getString("close_kind"),
                rs.getObject("opened_at", LocalDateTime.class),
                rs.getObject("closed_at", LocalDateTime.class),
                rs.getInt("version"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }
}
