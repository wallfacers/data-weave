package com.dataweave.master.infrastructure.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.dataweave.master.domain.Uuid7;
import com.dataweave.master.domain.incident.IncidentProposal;
import com.dataweave.master.domain.incident.ProposalStatuses;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** incident_proposal 表 JdbcTemplate 仓储：修复提案的创建、状态流转、发布/回滚版本回写。 */
@Repository
public class IncidentProposalRepository {

    private final JdbcTemplate jdbc;

    public IncidentProposalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID incidentId, long taskDefId, int baseVersionNo, String proposedContent,
                        String changeSummary, String evidenceJson) {
        UUID id = Uuid7.generate();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO incident_proposal (id, incident_id, task_def_id, base_version_no, " +
                "  proposed_content, change_summary, evidence_json, status, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, incidentId, taskDefId, baseVersionNo, proposedContent, changeSummary, evidenceJson,
                ProposalStatuses.PENDING, now, now);
        return id;
    }

    public Optional<IncidentProposal> findById(UUID id) {
        List<IncidentProposal> list = jdbc.query(
                "SELECT * FROM incident_proposal WHERE id = ?", (rs, n) -> map(rs), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<IncidentProposal> findByIncident(UUID incidentId) {
        return jdbc.query(
                "SELECT * FROM incident_proposal WHERE incident_id = ? ORDER BY created_at DESC",
                (rs, n) -> map(rs), incidentId);
    }

    /** 乐观 CAS 状态流转（PENDING→APPROVED/REJECTED/STALE 等）。 */
    public boolean casStatus(UUID id, String from, String to) {
        int rows = jdbc.update(
                "UPDATE incident_proposal SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                to, LocalDateTime.now(), id, from);
        return rows == 1;
    }

    /** 提案生成时关联闸门审批单 id（供审批端点据此反查 agent_action，不改状态）。 */
    public boolean linkAgentAction(UUID id, Long agentActionId) {
        int rows = jdbc.update(
                "UPDATE incident_proposal SET agent_action_id = ?, updated_at = ? WHERE id = ?",
                agentActionId, LocalDateTime.now(), id);
        return rows == 1;
    }

    public boolean approve(UUID id, Long agentActionId, Long approvedBy) {
        int rows = jdbc.update(
                "UPDATE incident_proposal SET status = ?, agent_action_id = ?, approved_by = ?, " +
                "  approved_at = ?, updated_at = ? WHERE id = ? AND status = ?",
                ProposalStatuses.APPROVED, agentActionId, approvedBy, LocalDateTime.now(), LocalDateTime.now(),
                id, ProposalStatuses.PENDING);
        return rows == 1;
    }

    public boolean markPublished(UUID id, int publishedVersionNo) {
        int rows = jdbc.update(
                "UPDATE incident_proposal SET status = ?, published_version_no = ?, updated_at = ? " +
                "WHERE id = ? AND status = ?",
                ProposalStatuses.PUBLISHED, publishedVersionNo, LocalDateTime.now(), id, ProposalStatuses.APPROVED);
        return rows == 1;
    }

    public boolean markVerified(UUID id, boolean success) {
        int rows = jdbc.update(
                "UPDATE incident_proposal SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                success ? ProposalStatuses.VERIFIED : ProposalStatuses.VERIFY_FAILED,
                LocalDateTime.now(), id, ProposalStatuses.PUBLISHED);
        return rows == 1;
    }

    public boolean markRolledBack(UUID id, int rollbackVersionNo) {
        int rows = jdbc.update(
                "UPDATE incident_proposal SET status = ?, rollback_version_no = ?, updated_at = ? " +
                "WHERE id = ? AND status = ?",
                ProposalStatuses.ROLLED_BACK, rollbackVersionNo, LocalDateTime.now(), id,
                ProposalStatuses.VERIFY_FAILED);
        return rows == 1;
    }

    private IncidentProposal map(ResultSet rs) throws SQLException {
        return new IncidentProposal(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("incident_id"),
                rs.getLong("task_def_id"),
                rs.getInt("base_version_no"),
                rs.getString("proposed_content"),
                rs.getString("change_summary"),
                rs.getString("evidence_json"),
                rs.getString("status"),
                rs.getObject("agent_action_id", Long.class),
                rs.getObject("published_version_no", Integer.class),
                rs.getObject("rollback_version_no", Integer.class),
                rs.getObject("approved_by", Long.class),
                rs.getObject("approved_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }
}
