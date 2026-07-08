package com.dataweave.master.infrastructure.lineage;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.dataweave.master.domain.lineage.LineageAgentConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 053 lineage_agent_config / lineage_agent_call 的 JdbcTemplate 仓储。
 * 按 tenant/project 隔离 + 软删（deleted=0）+ GeneratedKeyHolder 取主键；字符串拼接用 CONCAT 不用 ||（H2/PG 通用）。
 */
@Repository
public class AgentConfigRepository {

    private final JdbcTemplate jdbc;

    public AgentConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 取当前生效（deleted=0）配置；无则 empty。 */
    public Optional<LineageAgentConfig> findActive(long tenantId, long projectId) {
        List<LineageAgentConfig> list = jdbc.query(
                "SELECT * FROM lineage_agent_config WHERE tenant_id = ? AND project_id = ? AND deleted = 0",
                (rs, n) -> map(rs), tenantId, projectId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 更新现有配置。apiKeyEnc=null 时保留旧密文（PATCH null vs 缺失语义：apiKey 缺省=不改，记忆）。
     * @return 影响行数（0=不存在或已软删）。
     */
    public int update(Long id, String protocol, String baseUrl, String model, String apiKeyEnc,
                      boolean enabled, int timeoutMs, int rateLimitPerMin, int maxColumns, Long userId) {
        return jdbc.update(
                "UPDATE lineage_agent_config SET protocol = ?, base_url = ?, model = ?, " +
                "    api_key_enc = COALESCE(?, api_key_enc), enabled = ?, timeout_ms = ?, " +
                "    rate_limit_per_min = ?, max_columns = ?, updated_by = ?, " +
                "    updated_at = ?, version = version + 1 " +
                "WHERE id = ? AND deleted = 0",
                protocol, baseUrl, model, apiKeyEnc, enabled ? 1 : 0, timeoutMs,
                rateLimitPerMin, maxColumns, userId, LocalDateTime.now(), id);
    }

    /** 插入新配置；返回新 id（取自 GeneratedKeyHolder，H2/PG 通用，记忆 alert-jdbc-call-identity 教训）。 */
    public long insert(long tenantId, long projectId, String protocol, String baseUrl, String model,
                       String apiKeyEnc, boolean enabled, int timeoutMs, int rateLimitPerMin, int maxColumns, Long userId) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO lineage_agent_config (tenant_id, project_id, protocol, base_url, model, " +
                    "    api_key_enc, enabled, timeout_ms, rate_limit_per_min, max_columns, " +
                    "    created_by, updated_by, deleted, version) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,0)",
                    new String[]{"id"});
            int i = 0;
            ps.setLong(++i, tenantId);
            ps.setLong(++i, projectId);
            ps.setString(++i, protocol);
            ps.setString(++i, baseUrl);
            ps.setString(++i, model);
            ps.setString(++i, apiKeyEnc);
            ps.setInt(++i, enabled ? 1 : 0);
            ps.setInt(++i, timeoutMs);
            ps.setInt(++i, rateLimitPerMin);
            ps.setInt(++i, maxColumns);
            ps.setObject(++i, userId);
            ps.setObject(++i, userId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : -1L;
    }

    /** 写一次外呼审计记录（FR-021）。不含明文密钥/脚本。 */
    public void insertCall(long tenantId, long projectId, long configId, String protocol, Long taskDefId,
                           Integer latencyMs, String status, int edgesEmitted, String note) {
        jdbc.update(
                "INSERT INTO lineage_agent_call (tenant_id, project_id, config_id, protocol, task_def_id, " +
                "    latency_ms, status, edges_emitted, note) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
                tenantId, projectId, configId, protocol, taskDefId, latencyMs, status, edgesEmitted, note);
    }

    /** 查询最近 N 条外呼审计记录（FR-021/T034）。按创建时间倒序，脱敏返回（不含明文密钥/脚本）。 */
    public List<CallRecord> findCalls(long tenantId, long projectId, Long taskDefId, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, tenant_id, project_id, config_id, protocol, task_def_id, " +
                "latency_ms, status, edges_emitted, note, created_at " +
                "FROM lineage_agent_call WHERE tenant_id = ? AND project_id = ?");
        // 动态条件：taskDefId 可选
        if (taskDefId != null) {
            sql.append(" AND task_def_id = ?");
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        return jdbc.query(sql.toString(), rs -> {
            List<CallRecord> list = new java.util.ArrayList<>();
            while (rs.next()) {
                list.add(new CallRecord(
                        rs.getLong("id"),
                        rs.getLong("tenant_id"),
                        rs.getLong("project_id"),
                        rs.getLong("config_id"),
                        rs.getString("protocol"),
                        rs.getObject("task_def_id", Long.class),
                        rs.getObject("latency_ms", Integer.class),
                        rs.getString("status"),
                        rs.getInt("edges_emitted"),
                        rs.getString("note"),
                        rs.getObject("created_at", java.time.LocalDateTime.class)));
            }
            return list;
        }, tenantId, projectId, taskDefId != null ? new Object[]{tenantId, projectId, taskDefId, limit}
                : new Object[]{tenantId, projectId, limit});
    }

    /** 审计记录 VO（脱敏——不含明文 key/脚本，FR-020）。 */
    public record CallRecord(Long id, long tenantId, long projectId, long configId,
                             String protocol, Long taskDefId, Integer latencyMs,
                             String status, int edgesEmitted, String note,
                             java.time.LocalDateTime createdAt) {}

    private LineageAgentConfig map(ResultSet rs) throws java.sql.SQLException {
        return new LineageAgentConfig(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getLong("project_id"),
                rs.getString("protocol"),
                rs.getString("base_url"),
                rs.getString("model"),
                rs.getString("api_key_enc"),
                rs.getInt("enabled") == 1,
                rs.getInt("timeout_ms"),
                rs.getInt("rate_limit_per_min"),
                rs.getInt("max_columns"),
                rs.getObject("created_by", Long.class),
                rs.getObject("updated_by", Long.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getInt("deleted"),
                rs.getInt("version")
        );
    }
}
