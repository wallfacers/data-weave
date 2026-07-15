package com.dataweave.master.companion.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.dataweave.master.companion.domain.PatrolReport;
import com.dataweave.master.companion.domain.ReportStatuses;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code patrol_report} 表 JdbcTemplate 仓储——巡检汇报（项目级共享）。
 *
 * <p>卡片栈查询走 {@code idx_patrol_report_stack (project_id, status, created_at DESC)}。
 * 关闭为项目级共享：{@link #close} 仅在 {@code status <> CLOSED} 时写入，幂等成功由调用方（已 CLOSED 视为成功）。
 */
@Repository
public class JdbcPatrolReportRepository {

    private final JdbcTemplate jdbc;

    public JdbcPatrolReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 巡检产出落库（status 走 DDL 默认 UNREAD，closed_by/closed_at/created_at 默认 NULL/now）。 */
    public long insert(long tenantId, long projectId, Long runId, String domain, String severity,
                       String title, String summary, String detailJson, int aggregateCount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO patrol_report (tenant_id, project_id, run_id, domain, severity, title, " +
                    "summary, detail_json, aggregate_count) VALUES (?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            int i = 0;
            ps.setLong(++i, tenantId);
            ps.setLong(++i, projectId);
            if (runId != null) ps.setLong(++i, runId);
            else ps.setNull(++i, java.sql.Types.BIGINT);
            ps.setString(++i, domain);
            ps.setString(++i, severity);
            ps.setString(++i, title);
            ps.setString(++i, summary);
            ps.setString(++i, detailJson);
            ps.setInt(++i, aggregateCount);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("patrol_report INSERT 未返回主键");
        return key.longValue();
    }

    public Optional<PatrolReport> findById(long id) {
        List<PatrolReport> list = jdbc.query("SELECT * FROM patrol_report WHERE id = ?", (rs, n) -> map(rs), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** snapshot/卡片栈：项目内未关闭汇报，时间倒序。 */
    public List<PatrolReport> findOpenByProject(long tenantId, long projectId, int limit) {
        return jdbc.query(
                "SELECT * FROM patrol_report WHERE tenant_id = ? AND project_id = ? AND status <> ? " +
                "ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, n) -> map(rs), tenantId, projectId, ReportStatuses.CLOSED, limit);
    }

    /** REST 列表（补看/分页）：可选 status 过滤。 */
    public List<PatrolReport> findByProject(long tenantId, long projectId, String status, int limit) {
        if (status != null && !status.isBlank()) {
            return jdbc.query(
                    "SELECT * FROM patrol_report WHERE tenant_id = ? AND project_id = ? AND status = ? " +
                    "ORDER BY created_at DESC, id DESC LIMIT ?",
                    (rs, n) -> map(rs), tenantId, projectId, status, limit);
        }
        return jdbc.query(
                "SELECT * FROM patrol_report WHERE tenant_id = ? AND project_id = ? " +
                "ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, n) -> map(rs), tenantId, projectId, limit);
    }

    /** 执行历史↔汇报关联（US4-AS2）。 */
    public List<PatrolReport> findByRun(long runId) {
        return jdbc.query("SELECT * FROM patrol_report WHERE run_id = ? ORDER BY id ASC",
                (rs, n) -> map(rs), runId);
    }

    /**
     * 项目级关闭：仅在 {@code status <> CLOSED} 时写入 closed_by/closed_at。
     * 幂等语义（已关闭返回成功）由调用方处理；返回 true=本次完成关闭，false=已关闭或不存在。
     */
    public boolean close(long id, long tenantId, long projectId, String closedBy) {
        int rows = jdbc.update(
                "UPDATE patrol_report SET status = ?, closed_by = ?, closed_at = ? " +
                "WHERE id = ? AND tenant_id = ? AND project_id = ? AND status <> ?",
                ReportStatuses.CLOSED, closedBy, LocalDateTime.now(), id, tenantId, projectId, ReportStatuses.CLOSED);
        return rows >= 1;
    }

    /** 标记已读：UNREAD→READ（已读/已关闭时 no-op）。 */
    public boolean markRead(long id, long tenantId, long projectId) {
        int rows = jdbc.update(
                "UPDATE patrol_report SET status = ? WHERE id = ? AND tenant_id = ? AND project_id = ? AND status = ?",
                ReportStatuses.READ, id, tenantId, projectId, ReportStatuses.UNREAD);
        return rows >= 1;
    }

    /** 概况"未关闭异常数"：status<>CLOSED 且 severity∈{DANGER,WARN}。 */
    public int countOpenAnomalies(long tenantId, long projectId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM patrol_report WHERE tenant_id = ? AND project_id = ? " +
                "AND status <> ? AND severity IN (?, ?)",
                Integer.class, tenantId, projectId, ReportStatuses.CLOSED,
                com.dataweave.master.companion.domain.ReportSeverities.DANGER,
                com.dataweave.master.companion.domain.ReportSeverities.WARN);
        return c == null ? 0 : c;
    }

    /** 状态归一"alert"形态：是否存在未关闭异常（DANGER/WARN）。 */
    public boolean existsOpenAnomaly(long tenantId, long projectId) {
        return countOpenAnomalies(tenantId, projectId) > 0;
    }

    private PatrolReport map(ResultSet rs) throws SQLException {
        return new PatrolReport(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getLong("project_id"),
                rs.getObject("run_id", Long.class),
                rs.getString("domain"),
                rs.getString("severity"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("detail_json"),
                rs.getInt("aggregate_count"),
                rs.getString("status"),
                rs.getString("closed_by"),
                rs.getObject("closed_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
