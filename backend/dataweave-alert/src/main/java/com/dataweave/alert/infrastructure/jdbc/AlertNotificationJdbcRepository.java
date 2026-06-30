package com.dataweave.alert.infrastructure.jdbc;

import com.dataweave.alert.domain.AlertNotification;
import com.dataweave.alert.domain.repository.AlertNotificationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class AlertNotificationJdbcRepository implements AlertNotificationRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<AlertNotification> ROW_MAPPER = (rs, _) -> {
        AlertNotification n = new AlertNotification();
        n.setId(rs.getLong("id"));
        n.setTenantId(rs.getLong("tenant_id"));
        n.setEventId(rs.getLong("event_id"));
        n.setChannelId(rs.getLong("channel_id"));
        n.setStatus(rs.getString("status"));
        n.setAttempts(rs.getInt("attempts"));
        n.setSentAt(toLdt(rs.getTimestamp("sent_at")));
        n.setError(rs.getString("error"));
        n.setResponseDigest(rs.getString("response_digest"));
        n.setCreatedBy(rs.getObject("created_by", Long.class));
        n.setUpdatedBy(rs.getObject("updated_by", Long.class));
        n.setCreatedAt(toLdt(rs.getTimestamp("created_at")));
        n.setUpdatedAt(toLdt(rs.getTimestamp("updated_at")));
        n.setDeleted(rs.getInt("deleted"));
        n.setVersion(rs.getInt("version"));
        return n;
    };

    public AlertNotificationJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public AlertNotification save(AlertNotification n) {
        if (n.getId() == null) {
            long id = JdbcInsertSupport.insertReturningId(jdbc,
                    "INSERT INTO alert_notification (tenant_id, event_id, channel_id, status, attempts, " +
                    "sent_at, error, response_digest, created_by, created_at, deleted, version) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    n.getTenantId(), n.getEventId(), n.getChannelId(), n.getStatus(), n.getAttempts(),
                    n.getSentAt(), n.getError(), n.getResponseDigest(), n.getCreatedBy(), LocalDateTime.now(), 0, 0);
            n.setId(id);
        } else {
            jdbc.update(
                    "UPDATE alert_notification SET status=?, attempts=?, sent_at=?, error=?, response_digest=?, " +
                    "updated_at=?, version=version+1 WHERE id=? AND deleted=0",
                    n.getStatus(), n.getAttempts(), n.getSentAt(), n.getError(), n.getResponseDigest(),
                    LocalDateTime.now(), n.getId());
        }
        return n;
    }

    @Override
    public List<AlertNotification> findByEventId(Long tenantId, Long eventId) {
        return jdbc.query(
                "SELECT * FROM alert_notification WHERE tenant_id=? AND event_id=? AND deleted=0 ORDER BY id",
                ROW_MAPPER, tenantId, eventId);
    }

    @Override
    public int updateStatus(Long id, String status, String error, String responseDigest) {
        return jdbc.update(
                "UPDATE alert_notification SET status=?, error=?, response_digest=?, updated_at=? WHERE id=?",
                status, error, responseDigest, LocalDateTime.now(), id);
    }

    private static LocalDateTime toLdt(Timestamp ts) { return ts == null ? null : ts.toLocalDateTime(); }
}
