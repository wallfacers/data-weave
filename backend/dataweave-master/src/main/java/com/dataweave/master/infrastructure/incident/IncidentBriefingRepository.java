package com.dataweave.master.infrastructure.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.dataweave.master.domain.incident.IncidentBriefing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * incident_briefing 表 JdbcTemplate 仓储：每项目最新一行的战况播报（upsert 语义）。
 * stats_json 仅作报告佐证快照——实时数字查询另由调用方直接对 incident 表 SQL 现算（SC-010）。
 */
@Repository
public class IncidentBriefingRepository {

    private final JdbcTemplate jdbc;

    public IncidentBriefingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IncidentBriefing> findByProject(long tenantId, long projectId) {
        List<IncidentBriefing> list = jdbc.query(
                "SELECT * FROM incident_briefing WHERE tenant_id = ? AND project_id = ?",
                (rs, n) -> map(rs), tenantId, projectId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** upsert：先 UPDATE，0 行受影响则 INSERT（H2/PG 通用，无需 MERGE/ON CONFLICT 方言）。 */
    public void upsert(long tenantId, long projectId, String summaryLine, String reportMd, String statsJson) {
        LocalDateTime now = LocalDateTime.now();
        int rows = jdbc.update(
                "UPDATE incident_briefing SET summary_line = ?, report_md = ?, stats_json = ?, generated_at = ? " +
                "WHERE tenant_id = ? AND project_id = ?",
                summaryLine, reportMd, statsJson, now, tenantId, projectId);
        if (rows == 0) {
            jdbc.update(
                    "INSERT INTO incident_briefing (tenant_id, project_id, summary_line, report_md, " +
                    "  stats_json, generated_at) VALUES (?,?,?,?,?,?)",
                    tenantId, projectId, summaryLine, reportMd, statsJson, now);
        }
    }

    private IncidentBriefing map(ResultSet rs) throws SQLException {
        return new IncidentBriefing(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getLong("project_id"),
                rs.getString("summary_line"),
                rs.getString("report_md"),
                rs.getString("stats_json"),
                rs.getObject("generated_at", LocalDateTime.class));
    }
}
