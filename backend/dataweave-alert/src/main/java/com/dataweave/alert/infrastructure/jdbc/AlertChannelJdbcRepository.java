package com.dataweave.alert.infrastructure.jdbc;

import com.dataweave.alert.domain.AlertChannel;
import com.dataweave.alert.domain.repository.AlertChannelRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Repository
public class AlertChannelJdbcRepository implements AlertChannelRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<AlertChannel> ROW_MAPPER = (rs, _) -> {
        AlertChannel c = new AlertChannel();
        c.setId(rs.getLong("id"));
        c.setTenantId(rs.getLong("tenant_id"));
        c.setName(rs.getString("name"));
        c.setType(rs.getString("type"));
        c.setConfigJson(rs.getString("config_json"));
        c.setRateLimitPerMin(rs.getInt("rate_limit_per_min"));
        c.setEnabled(rs.getInt("enabled"));
        c.setCreatedBy(rs.getObject("created_by", Long.class));
        c.setUpdatedBy(rs.getObject("updated_by", Long.class));
        c.setCreatedAt(toLdt(rs.getTimestamp("created_at")));
        c.setUpdatedAt(toLdt(rs.getTimestamp("updated_at")));
        c.setDeleted(rs.getInt("deleted"));
        c.setVersion(rs.getInt("version"));
        return c;
    };

    public AlertChannelJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<AlertChannel> findById(Long id) {
        var list = jdbc.query("SELECT * FROM alert_channel WHERE id=? AND deleted=0", ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<AlertChannel> findByTenantId(Long tenantId) {
        return jdbc.query("SELECT * FROM alert_channel WHERE tenant_id=? AND deleted=0 ORDER BY id", ROW_MAPPER, tenantId);
    }

    @Override
    public List<AlertChannel> findByIds(Long tenantId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        return jdbc.query(
                "SELECT * FROM alert_channel WHERE tenant_id=? AND id IN (" + placeholders + ") AND deleted=0 ORDER BY id",
                ROW_MAPPER, Stream.concat(Stream.of(tenantId), ids.stream()).toArray());
    }

    @Override
    public AlertChannel save(AlertChannel c) {
        if (c.getId() == null) {
            return insert(c);
        }
        return update(c);
    }

    private AlertChannel insert(AlertChannel c) {
        long id = JdbcInsertSupport.insertReturningId(jdbc,
                "INSERT INTO alert_channel (tenant_id, name, type, config_json, rate_limit_per_min, enabled, " +
                "created_by, created_at, deleted, version) VALUES (?,?,?,?,?,?,?,?,?,?)",
                c.getTenantId(), c.getName(), c.getType(), c.getConfigJson(), c.getRateLimitPerMin(),
                c.getEnabled(), c.getCreatedBy(), LocalDateTime.now(), 0, 0);
        c.setId(id);
        return c;
    }

    private AlertChannel update(AlertChannel c) {
        jdbc.update(
                "UPDATE alert_channel SET name=?, type=?, config_json=?, rate_limit_per_min=?, enabled=?, " +
                "updated_by=?, updated_at=?, version=version+1 WHERE id=? AND deleted=0",
                c.getName(), c.getType(), c.getConfigJson(), c.getRateLimitPerMin(), c.getEnabled(),
                c.getUpdatedBy(), LocalDateTime.now(), c.getId());
        return c;
    }

    @Override
    public int deleteById(Long id) {
        return jdbc.update("UPDATE alert_channel SET deleted=1 WHERE id=? AND deleted=0", id);
    }

    private static LocalDateTime toLdt(Timestamp ts) { return ts == null ? null : ts.toLocalDateTime(); }
}
