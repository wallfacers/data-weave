package com.dataweave.alert.infrastructure.jdbc;

import com.dataweave.alert.domain.AlertRule;
import com.dataweave.alert.domain.repository.AlertRuleRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AlertRuleJdbcRepository implements AlertRuleRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<AlertRule> ROW_MAPPER = (rs, _) -> {
        AlertRule r = new AlertRule();
        r.setId(rs.getLong("id"));
        r.setTenantId(rs.getLong("tenant_id"));
        r.setName(rs.getString("name"));
        r.setDescription(rs.getString("description"));
        r.setEnabled(rs.getInt("enabled"));
        r.setSignalSource(rs.getString("signal_source"));
        r.setEvalMode(rs.getString("eval_mode"));
        r.setEvalIntervalSec(rs.getObject("eval_interval_sec", Integer.class));
        r.setConditionJson(rs.getString("condition_json"));
        r.setSeverity(rs.getString("severity"));
        r.setForDuration(rs.getInt("for_duration"));
        r.setDedupKeyTemplate(rs.getString("dedup_key_template"));
        r.setSuppressWindowSec(rs.getInt("suppress_window_sec"));
        r.setAutoResolve(rs.getInt("auto_resolve"));
        r.setLabelsJson(rs.getString("labels_json"));
        r.setCreatedBy(rs.getObject("created_by", Long.class));
        r.setUpdatedBy(rs.getObject("updated_by", Long.class));
        r.setCreatedAt(toLdt(rs.getTimestamp("created_at")));
        r.setUpdatedAt(toLdt(rs.getTimestamp("updated_at")));
        r.setDeleted(rs.getInt("deleted"));
        r.setVersion(rs.getInt("version"));
        return r;
    };

    public AlertRuleJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<AlertRule> findById(Long id) {
        var list = jdbc.query(
                "SELECT * FROM alert_rule WHERE id = ? AND deleted = 0", ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<AlertRule> findByTenantIdAndSignalSourceAndEnabled(Long tenantId, String signalSource, Integer enabled) {
        return jdbc.query(
                "SELECT * FROM alert_rule WHERE tenant_id = ? AND signal_source = ? AND enabled = ? AND deleted = 0",
                ROW_MAPPER, tenantId, signalSource, enabled);
    }

    @Override
    public List<AlertRule> findByTenantIdAndEvalModeAndEnabled(Long tenantId, String evalMode, Integer enabled) {
        return jdbc.query(
                "SELECT * FROM alert_rule WHERE tenant_id = ? AND eval_mode = ? AND enabled = ? AND deleted = 0",
                ROW_MAPPER, tenantId, evalMode, enabled);
    }

    @Override
    public List<AlertRule> findByEvalModeAndEnabled(String evalMode, Integer enabled) {
        // 026: 不带 tenant 过滤——全租户 POLL 轮询
        return jdbc.query(
                "SELECT * FROM alert_rule WHERE eval_mode = ? AND enabled = ? AND deleted = 0",
                ROW_MAPPER, evalMode, enabled);
    }

    @Override
    public List<AlertRule> findByTenantId(Long tenantId, int offset, int limit) {
        return jdbc.query(
                "SELECT * FROM alert_rule WHERE tenant_id = ? AND deleted = 0 ORDER BY id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, tenantId, limit, offset);
    }

    @Override
    public int countByTenantId(Long tenantId) {
        var r = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_rule WHERE tenant_id = ? AND deleted = 0",
                Integer.class, tenantId);
        return r != null ? r : 0;
    }

    @Override
    public AlertRule save(AlertRule rule) {
        if (rule.getId() == null) {
            return insert(rule);
        }
        return update(rule);
    }

    private AlertRule insert(AlertRule r) {
        long generated = JdbcInsertSupport.insertReturningId(jdbc,
                "INSERT INTO alert_rule (tenant_id, name, description, enabled, signal_source, eval_mode, " +
                "eval_interval_sec, condition_json, severity, for_duration, dedup_key_template, " +
                "suppress_window_sec, auto_resolve, labels_json, created_by, created_at, deleted, version) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.getTenantId(), r.getName(), r.getDescription(), r.getEnabled(), r.getSignalSource(),
                r.getEvalMode(), r.getEvalIntervalSec(), r.getConditionJson(), r.getSeverity(),
                r.getForDuration(), r.getDedupKeyTemplate(), r.getSuppressWindowSec(), r.getAutoResolve(),
                r.getLabelsJson(), r.getCreatedBy(), LocalDateTime.now(), 0, 0);
        r.setId(generated);
        return r;
    }

    private AlertRule update(AlertRule r) {
        jdbc.update(
                "UPDATE alert_rule SET name=?, description=?, enabled=?, signal_source=?, eval_mode=?, " +
                "eval_interval_sec=?, condition_json=?, severity=?, for_duration=?, dedup_key_template=?, " +
                "suppress_window_sec=?, auto_resolve=?, labels_json=?, updated_by=?, updated_at=?, version=version+1 " +
                "WHERE id=? AND deleted=0",
                r.getName(), r.getDescription(), r.getEnabled(), r.getSignalSource(), r.getEvalMode(),
                r.getEvalIntervalSec(), r.getConditionJson(), r.getSeverity(), r.getForDuration(),
                r.getDedupKeyTemplate(), r.getSuppressWindowSec(), r.getAutoResolve(), r.getLabelsJson(),
                r.getUpdatedBy(), LocalDateTime.now(), r.getId());
        return r;
    }

    @Override
    public int deleteById(Long id) {
        return jdbc.update("UPDATE alert_rule SET deleted=1 WHERE id=? AND deleted=0", id);
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
