package com.dataweave.alert.infrastructure.jdbc;

import com.dataweave.alert.domain.AlertEvent;
import com.dataweave.alert.domain.repository.AlertEventRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AlertEventJdbcRepository implements AlertEventRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<AlertEvent> ROW_MAPPER = (rs, _) -> {
        AlertEvent e = new AlertEvent();
        e.setId(rs.getLong("id"));
        e.setTenantId(rs.getLong("tenant_id"));
        e.setRuleId(rs.getLong("rule_id"));
        e.setState(rs.getString("state"));
        e.setSeverity(rs.getString("severity"));
        e.setFingerprint(rs.getString("fingerprint"));
        e.setValue(rs.getString("value"));
        e.setContextJson(rs.getString("context_json"));
        e.setCount(rs.getInt("count"));
        e.setFirstFiredAt(toLdt(rs.getTimestamp("first_fired_at")));
        e.setLastFiredAt(toLdt(rs.getTimestamp("last_fired_at")));
        e.setResolvedAt(toLdt(rs.getTimestamp("resolved_at")));
        e.setAckedBy(rs.getObject("acked_by", Long.class));
        e.setAckedAt(toLdt(rs.getTimestamp("acked_at")));
        e.setCreatedBy(rs.getObject("created_by", Long.class));
        e.setUpdatedBy(rs.getObject("updated_by", Long.class));
        e.setCreatedAt(toLdt(rs.getTimestamp("created_at")));
        e.setUpdatedAt(toLdt(rs.getTimestamp("updated_at")));
        e.setDeleted(rs.getInt("deleted"));
        e.setVersion(rs.getInt("version"));
        return e;
    };

    public AlertEventJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<AlertEvent> findById(Long id) {
        var list = jdbc.query("SELECT * FROM alert_event WHERE id=? AND deleted=0", ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<AlertEvent> findByTenantIdAndFingerprintAndState(Long tenantId, String fingerprint, String state) {
        var list = jdbc.query(
                "SELECT * FROM alert_event WHERE tenant_id=? AND fingerprint=? AND state=? AND deleted=0",
                ROW_MAPPER, tenantId, fingerprint, state);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<AlertEvent> findByTenantIdAndState(Long tenantId, String state, int offset, int limit) {
        return jdbc.query(
                "SELECT * FROM alert_event WHERE tenant_id=? AND state=? AND deleted=0 ORDER BY last_fired_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, tenantId, state, limit, offset);
    }

    @Override
    public int countByTenantIdAndState(Long tenantId, String state) {
        var r = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_event WHERE tenant_id=? AND state=? AND deleted=0",
                Integer.class, tenantId, state);
        return r != null ? r : 0;
    }

    @Override
    public List<AlertEvent> findByTenantId(Long tenantId, int offset, int limit) {
        return jdbc.query(
                "SELECT * FROM alert_event WHERE tenant_id=? AND deleted=0 ORDER BY last_fired_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, tenantId, limit, offset);
    }

    @Override
    public int countByTenantId(Long tenantId) {
        var r = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_event WHERE tenant_id=? AND deleted=0",
                Integer.class, tenantId);
        return r != null ? r : 0;
    }

    @Override
    public AlertEvent save(AlertEvent e) {
        if (e.getId() == null) {
            return insert(e);
        }
        return update(e);
    }

    private AlertEvent insert(AlertEvent e) {
        jdbc.update(
                "INSERT INTO alert_event (tenant_id, rule_id, state, severity, fingerprint, \"value\", " +
                "context_json, count, first_fired_at, last_fired_at, resolved_at, acked_by, acked_at, " +
                "created_by, created_at, deleted, version) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                e.getTenantId(), e.getRuleId(), e.getState(), e.getSeverity(), e.getFingerprint(),
                e.getValue(), e.getContextJson(), e.getCount(), e.getFirstFiredAt(), e.getLastFiredAt(),
                e.getResolvedAt(), e.getAckedBy(), e.getAckedAt(), e.getCreatedBy(), LocalDateTime.now(), 0, 0);
        e.setId(jdbc.queryForObject("CALL IDENTITY()", Long.class));
        return e;
    }

    private AlertEvent update(AlertEvent e) {
        jdbc.update(
                "UPDATE alert_event SET state=?, severity=?, count=?, last_fired_at=?, resolved_at=?, " +
                "acked_by=?, acked_at=?, updated_by=?, updated_at=?, version=version+1 WHERE id=? AND deleted=0",
                e.getState(), e.getSeverity(), e.getCount(), e.getLastFiredAt(), e.getResolvedAt(),
                e.getAckedBy(), e.getAckedAt(), e.getUpdatedBy(), LocalDateTime.now(), e.getId());
        return e;
    }

    @Override
    public boolean casState(Long id, String expectedState, String newState) {
        int n = jdbc.update(
                "UPDATE alert_event SET state=?, updated_at=? WHERE id=? AND state=? AND deleted=0",
                newState, LocalDateTime.now(), id, expectedState);
        return n == 1;
    }

    @Override
    public int incrementCount(Long id, Integer newCount) {
        return jdbc.update(
                "UPDATE alert_event SET count=?, last_fired_at=?, updated_at=? WHERE id=? AND deleted=0",
                newCount, LocalDateTime.now(), LocalDateTime.now(), id);
    }

    @Override
    public int markResolved(Long id) {
        return jdbc.update(
                "UPDATE alert_event SET state='RESOLVED', resolved_at=?, updated_at=? WHERE id=? AND deleted=0",
                LocalDateTime.now(), LocalDateTime.now(), id);
    }

    @Override
    public int markAcked(Long id, Long ackedBy) {
        return jdbc.update(
                "UPDATE alert_event SET state='ACKED', acked_by=?, acked_at=?, updated_at=? WHERE id=? AND deleted=0",
                ackedBy, LocalDateTime.now(), LocalDateTime.now(), id);
    }

    @Override
    public int markSuppressed(Long id) {
        return jdbc.update(
                "UPDATE alert_event SET state='SUPPRESSED', updated_at=? WHERE id=? AND deleted=0",
                LocalDateTime.now(), id);
    }

    private static LocalDateTime toLdt(Timestamp ts) { return ts == null ? null : ts.toLocalDateTime(); }
}
