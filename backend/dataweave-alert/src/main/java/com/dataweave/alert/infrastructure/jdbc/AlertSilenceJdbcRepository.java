package com.dataweave.alert.infrastructure.jdbc;

import com.dataweave.alert.domain.AlertSilence;
import com.dataweave.alert.domain.repository.AlertSilenceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AlertSilenceJdbcRepository implements AlertSilenceRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<AlertSilence> ROW_MAPPER = (rs, _) -> {
        AlertSilence s = new AlertSilence();
        s.setId(rs.getLong("id"));
        s.setTenantId(rs.getLong("tenant_id"));
        s.setMatchJson(rs.getString("match_json"));
        s.setStartsAt(toLdt(rs.getTimestamp("starts_at")));
        s.setEndsAt(toLdt(rs.getTimestamp("ends_at")));
        s.setReason(rs.getString("reason"));
        s.setCreator(rs.getObject("creator", Long.class));
        s.setCreatedBy(rs.getObject("created_by", Long.class));
        s.setUpdatedBy(rs.getObject("updated_by", Long.class));
        s.setCreatedAt(toLdt(rs.getTimestamp("created_at")));
        s.setUpdatedAt(toLdt(rs.getTimestamp("updated_at")));
        s.setDeleted(rs.getInt("deleted"));
        s.setVersion(rs.getInt("version"));
        return s;
    };

    public AlertSilenceJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<AlertSilence> findById(Long id) {
        var list = jdbc.query("SELECT * FROM alert_silence WHERE id=? AND deleted=0", ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<AlertSilence> findActiveByTenantId(Long tenantId) {
        LocalDateTime now = LocalDateTime.now();
        return jdbc.query(
                "SELECT * FROM alert_silence WHERE tenant_id=? AND starts_at <= ? AND ends_at >= ? AND deleted=0 ORDER BY starts_at",
                ROW_MAPPER, tenantId, now, now);
    }

    @Override
    public AlertSilence save(AlertSilence s) {
        if (s.getId() == null) {
            return insert(s);
        }
        return update(s);
    }

    private AlertSilence insert(AlertSilence s) {
        long id = JdbcInsertSupport.insertReturningId(jdbc,
                "INSERT INTO alert_silence (tenant_id, match_json, starts_at, ends_at, reason, creator, " +
                "created_by, created_at, deleted, version) VALUES (?,?,?,?,?,?,?,?,?,?)",
                s.getTenantId(), s.getMatchJson(), s.getStartsAt(), s.getEndsAt(), s.getReason(),
                s.getCreator(), s.getCreatedBy(), LocalDateTime.now(), 0, 0);
        s.setId(id);
        return s;
    }

    private AlertSilence update(AlertSilence s) {
        jdbc.update(
                "UPDATE alert_silence SET match_json=?, starts_at=?, ends_at=?, reason=?, " +
                "updated_by=?, updated_at=?, version=version+1 WHERE id=? AND deleted=0",
                s.getMatchJson(), s.getStartsAt(), s.getEndsAt(), s.getReason(),
                s.getUpdatedBy(), LocalDateTime.now(), s.getId());
        return s;
    }

    @Override
    public int deleteById(Long id) {
        return jdbc.update("UPDATE alert_silence SET deleted=1 WHERE id=? AND deleted=0", id);
    }

    private static LocalDateTime toLdt(Timestamp ts) { return ts == null ? null : ts.toLocalDateTime(); }
}
