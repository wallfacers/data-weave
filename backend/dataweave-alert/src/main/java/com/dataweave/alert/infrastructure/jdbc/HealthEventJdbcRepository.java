package com.dataweave.alert.infrastructure.jdbc;

import com.dataweave.alert.domain.HealthEvent;
import com.dataweave.alert.domain.repository.HealthEventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class HealthEventJdbcRepository implements HealthEventRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<HealthEvent> ROW_MAPPER = (rs, _) -> {
        HealthEvent e = new HealthEvent();
        e.setId(rs.getLong("id"));
        e.setTenantId(rs.getLong("tenant_id"));
        e.setType(rs.getString("type"));
        e.setSeverity(rs.getString("severity"));
        e.setFingerprint(rs.getString("fingerprint"));
        e.setRefKind(rs.getString("ref_kind"));
        e.setRefId(rs.getString("ref_id"));
        e.setSummary(rs.getString("summary"));
        e.setContextJson(rs.getString("context_json"));
        e.setCount(rs.getInt("count"));
        e.setFirstOccurredAt(toLdt(rs.getTimestamp("first_occurred_at")));
        e.setLastOccurredAt(toLdt(rs.getTimestamp("last_occurred_at")));
        e.setCreatedAt(toLdt(rs.getTimestamp("created_at")));
        e.setDeleted(rs.getInt("deleted"));
        return e;
    };

    public HealthEventJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void record(HealthEvent e) {
        LocalDateTime now = e.getLastOccurredAt() != null ? e.getLastOccurredAt() : LocalDateTime.now();
        // 去重 upsert：先 UPDATE（命中 = count++/刷新）；0 行则 INSERT；INSERT 撞唯一索引（并发）→ 退回 UPDATE。
        int updated = updateExisting(e, now);
        if (updated > 0) return;
        try {
            insertNew(e, now);
        } catch (DataIntegrityViolationException race) {
            updateExisting(e, now);
        }
    }

    private int updateExisting(HealthEvent e, LocalDateTime now) {
        return jdbc.update(
                "UPDATE health_event SET count = count + 1, last_occurred_at = ?, severity = ?, " +
                "summary = ?, context_json = ?, ref_kind = ?, ref_id = ? " +
                "WHERE tenant_id = ? AND type = ? AND fingerprint = ? AND deleted = 0",
                now, e.getSeverity(), e.getSummary(), e.getContextJson(), e.getRefKind(), e.getRefId(),
                e.getTenantId(), e.getType(), e.getFingerprint());
    }

    private void insertNew(HealthEvent e, LocalDateTime now) {
        jdbc.update(
                "INSERT INTO health_event (tenant_id, type, severity, fingerprint, ref_kind, ref_id, " +
                "summary, context_json, count, first_occurred_at, last_occurred_at, created_at, deleted) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                e.getTenantId(), e.getType(), e.getSeverity(), e.getFingerprint(), e.getRefKind(), e.getRefId(),
                e.getSummary(), e.getContextJson(), 1, now, now, now, 0);
    }

    @Override
    public List<HealthEvent> query(long tenantId, String type, String severity, String refKind, String refId,
                                   LocalDateTime from, LocalDateTime to, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM health_event WHERE tenant_id = ? AND deleted = 0");
        args.add(tenantId);
        appendFilters(sql, args, type, severity, refKind, refId, from, to);
        sql.append(" ORDER BY last_occurred_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    @Override
    public int count(long tenantId, String type, String severity, String refKind, String refId,
                     LocalDateTime from, LocalDateTime to) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM health_event WHERE tenant_id = ? AND deleted = 0");
        args.add(tenantId);
        appendFilters(sql, args, type, severity, refKind, refId, from, to);
        Integer n = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return n != null ? n : 0;
    }

    private void appendFilters(StringBuilder sql, List<Object> args, String type, String severity,
                               String refKind, String refId, LocalDateTime from, LocalDateTime to) {
        if (type != null && !type.isBlank()) { sql.append(" AND type = ?"); args.add(type); }
        if (severity != null && !severity.isBlank()) { sql.append(" AND severity = ?"); args.add(severity); }
        if (refKind != null && !refKind.isBlank()) { sql.append(" AND ref_kind = ?"); args.add(refKind); }
        if (refId != null && !refId.isBlank()) { sql.append(" AND ref_id = ?"); args.add(refId); }
        if (from != null) { sql.append(" AND last_occurred_at >= ?"); args.add(from); }
        if (to != null) { sql.append(" AND last_occurred_at <= ?"); args.add(to); }
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
