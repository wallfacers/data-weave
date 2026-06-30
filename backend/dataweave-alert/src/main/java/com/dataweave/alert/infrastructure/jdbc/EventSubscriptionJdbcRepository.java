package com.dataweave.alert.infrastructure.jdbc;

import com.dataweave.alert.domain.EventSubscription;
import com.dataweave.alert.domain.repository.EventSubscriptionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class EventSubscriptionJdbcRepository implements EventSubscriptionRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<EventSubscription> ROW_MAPPER = (rs, _) -> {
        EventSubscription s = new EventSubscription();
        s.setId(rs.getLong("id"));
        s.setTenantId(rs.getLong("tenant_id"));
        s.setSubscriberId(rs.getObject("subscriber_id", Long.class));
        s.setTypeFilter(rs.getString("type_filter"));
        s.setMinSeverity(rs.getString("min_severity"));
        s.setRefKind(rs.getString("ref_kind"));
        s.setRefId(rs.getString("ref_id"));
        s.setChannelId(rs.getLong("channel_id"));
        s.setEnabled(rs.getInt("enabled"));
        s.setCreatedAt(toLdt(rs.getTimestamp("created_at")));
        s.setDeleted(rs.getInt("deleted"));
        return s;
    };

    public EventSubscriptionJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public EventSubscription save(EventSubscription s) {
        long id = JdbcInsertSupport.insertReturningId(jdbc,
                "INSERT INTO event_subscription (tenant_id, subscriber_id, type_filter, min_severity, " +
                "ref_kind, ref_id, channel_id, enabled, created_at, deleted) VALUES (?,?,?,?,?,?,?,?,?,?)",
                s.getTenantId(), s.getSubscriberId(), s.getTypeFilter(), s.getMinSeverity(),
                s.getRefKind(), s.getRefId(), s.getChannelId(),
                s.getEnabled() != null ? s.getEnabled() : 1, LocalDateTime.now(), 0);
        s.setId(id);
        return s;
    }

    @Override
    public Optional<EventSubscription> findById(Long id) {
        var list = jdbc.query("SELECT * FROM event_subscription WHERE id = ? AND deleted = 0", ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<EventSubscription> findByTenantId(long tenantId) {
        return jdbc.query("SELECT * FROM event_subscription WHERE tenant_id = ? AND deleted = 0 ORDER BY id DESC",
                ROW_MAPPER, tenantId);
    }

    @Override
    public List<EventSubscription> findEnabledByTenantId(long tenantId) {
        return jdbc.query("SELECT * FROM event_subscription WHERE tenant_id = ? AND enabled = 1 AND deleted = 0",
                ROW_MAPPER, tenantId);
    }

    @Override
    public int deleteById(Long id) {
        return jdbc.update("UPDATE event_subscription SET deleted = 1 WHERE id = ? AND deleted = 0", id);
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
