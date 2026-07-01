package com.dataweave.alert.infrastructure.jdbc;

import com.dataweave.alert.domain.AlertRoute;
import com.dataweave.alert.domain.repository.AlertRouteRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AlertRouteJdbcRepository implements AlertRouteRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<AlertRoute> ROW_MAPPER = (rs, _) -> {
        AlertRoute r = new AlertRoute();
        r.setId(rs.getLong("id"));
        r.setTenantId(rs.getLong("tenant_id"));
        r.setProjectId(rs.getObject("project_id", Long.class));
        r.setMatchJson(rs.getString("match_json"));
        r.setChannelIds(rs.getString("channel_ids"));
        r.setSortOrder(rs.getInt("sort_order"));
        r.setEnabled(rs.getInt("enabled"));
        r.setCreatedBy(rs.getObject("created_by", Long.class));
        r.setUpdatedBy(rs.getObject("updated_by", Long.class));
        r.setCreatedAt(toLdt(rs.getTimestamp("created_at")));
        r.setUpdatedAt(toLdt(rs.getTimestamp("updated_at")));
        r.setDeleted(rs.getInt("deleted"));
        r.setVersion(rs.getInt("version"));
        return r;
    };

    public AlertRouteJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<AlertRoute> findById(Long id) {
        var list = jdbc.query("SELECT * FROM alert_route WHERE id=? AND deleted=0", ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<AlertRoute> findByTenantId(Long tenantId) {
        return jdbc.query("SELECT * FROM alert_route WHERE tenant_id=? AND deleted=0 ORDER BY sort_order, id", ROW_MAPPER, tenantId);
    }

    @Override
    public List<AlertRoute> findByTenantIdAndProjectId(Long tenantId, Long projectId) {
        return jdbc.query("SELECT * FROM alert_route WHERE tenant_id=? AND project_id=? AND deleted=0 ORDER BY sort_order, id", ROW_MAPPER, tenantId, projectId);
    }

    @Override
    public AlertRoute save(AlertRoute r) {
        if (r.getId() == null) {
            return insert(r);
        }
        return update(r);
    }

    private AlertRoute insert(AlertRoute r) {
        long id = JdbcInsertSupport.insertReturningId(jdbc,
                "INSERT INTO alert_route (tenant_id, project_id, match_json, channel_ids, sort_order, enabled, " +
                "created_by, created_at, deleted, version) VALUES (?,?,?,?,?,?,?,?,?,?)",
                r.getTenantId(), r.getProjectId(), r.getMatchJson(), r.getChannelIds(), r.getSortOrder(), r.getEnabled(),
                r.getCreatedBy(), LocalDateTime.now(), 0, 0);
        r.setId(id);
        return r;
    }

    private AlertRoute update(AlertRoute r) {
        jdbc.update(
                "UPDATE alert_route SET match_json=?, channel_ids=?, sort_order=?, enabled=?, " +
                "updated_by=?, updated_at=?, version=version+1 WHERE id=? AND deleted=0",
                r.getMatchJson(), r.getChannelIds(), r.getSortOrder(), r.getEnabled(),
                r.getUpdatedBy(), LocalDateTime.now(), r.getId());
        return r;
    }

    @Override
    public int deleteById(Long id) {
        return jdbc.update("UPDATE alert_route SET deleted=1 WHERE id=? AND deleted=0", id);
    }

    private static LocalDateTime toLdt(Timestamp ts) { return ts == null ? null : ts.toLocalDateTime(); }
}
