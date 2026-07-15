package com.dataweave.master.companion.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.dataweave.master.companion.domain.PatrolRoutine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code patrol_routine} 表 JdbcTemplate 仓储。
 *
 * <p>项目内按领域唯一；{@code findAllEnabled} 跨租户扫描（后台巡检用，同 IncidentRepository.findAllByState
 * 全局扫描惯例，无 tenant 过滤），其余查询显式 {@code WHERE tenant_id=? AND project_id=?}（036 项目隔离）。
 * PATCH 走 {@code version} 乐观锁（调度不变量② 的同族约束）。
 */
@Repository
public class JdbcPatrolRoutineRepository {

    private final JdbcTemplate jdbc;

    public JdbcPatrolRoutineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PatrolRoutine> findById(long id) {
        List<PatrolRoutine> list = jdbc.query(
                "SELECT * FROM patrol_routine WHERE id = ? AND deleted = 0", (rs, n) -> map(rs), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<PatrolRoutine> findByProjectAndDomain(long tenantId, long projectId, String domain) {
        List<PatrolRoutine> list = jdbc.query(
                "SELECT * FROM patrol_routine WHERE tenant_id = ? AND project_id = ? AND domain = ? AND deleted = 0",
                (rs, n) -> map(rs), tenantId, projectId, domain);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** US4 治理列表：项目内四领域例程与状态。 */
    public List<PatrolRoutine> findByProject(long tenantId, long projectId) {
        return jdbc.query(
                "SELECT * FROM patrol_routine WHERE tenant_id = ? AND project_id = ? AND deleted = 0 ORDER BY id ASC",
                (rs, n) -> map(rs), tenantId, projectId);
    }

    /**
     * 巡检调度扫描用：跨租户全部启用的例程（后台巡检同 TimeoutSweeper/IncidentSweeper 全局扫描惯例）。
     * scheduler 按 routine 自身的 tenantId/projectId 巡检与产出，互不串扰。
     */
    public List<PatrolRoutine> findAllEnabled() {
        return jdbc.query(
                "SELECT * FROM patrol_routine WHERE enabled = 1 AND deleted = 0 ORDER BY id ASC",
                (rs, n) -> map(rs));
    }

    /** 新建例程（多项目 lazy-seed / 治理创建 / 测试）。UNIQUE(project_id, domain) 冲突抛 DuplicateKeyException。 */
    public long insert(long tenantId, long projectId, String domain, boolean enabled, String cronExpression,
                       String scopeJson, int timeoutSeconds, Long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO patrol_routine (tenant_id, project_id, domain, enabled, cron_expression, " +
                    "scope_json, timeout_seconds, created_by, updated_by, created_at, updated_at, deleted, version) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,0,0)",
                    new String[]{"id"});
            int i = 0;
            ps.setLong(++i, tenantId);
            ps.setLong(++i, projectId);
            ps.setString(++i, domain);
            ps.setInt(++i, enabled ? 1 : 0);
            ps.setString(++i, cronExpression);
            ps.setString(++i, scopeJson);
            ps.setInt(++i, timeoutSeconds);
            if (createdBy != null) ps.setLong(++i, createdBy);
            else ps.setNull(++i, java.sql.Types.BIGINT);
            if (createdBy != null) ps.setLong(++i, createdBy);
            else ps.setNull(++i, java.sql.Types.BIGINT);
            ps.setObject(++i, now);
            ps.setObject(++i, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("patrol_routine INSERT 未返回主键");
        return key.longValue();
    }

    /**
     * US4 PATCH 治理：乐观锁更新启停/cron/范围。缺失字段由调用方回填当前值（PATCH 缺失=不改语义）；
     * 显式清空 scope 由调用方传 {@code null}（scope_json 列可空）。{@code version} 不符返回 false。
     */
    public boolean update(long id, long tenantId, long projectId, boolean enabled, String cronExpression,
                          String scopeJson, Long updatedBy, int currentVersion) {
        int rows = jdbc.update(
                "UPDATE patrol_routine SET enabled = ?, cron_expression = ?, scope_json = ?, " +
                "updated_by = ?, updated_at = ?, version = version + 1 " +
                "WHERE id = ? AND tenant_id = ? AND project_id = ? AND version = ? AND deleted = 0",
                enabled ? 1 : 0, cronExpression, scopeJson, updatedBy, LocalDateTime.now(),
                id, tenantId, projectId, currentVersion);
        return rows == 1;
    }

    private PatrolRoutine map(ResultSet rs) throws SQLException {
        return new PatrolRoutine(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getLong("project_id"),
                rs.getString("domain"),
                rs.getInt("enabled") == 1,
                rs.getString("cron_expression"),
                rs.getString("scope_json"),
                rs.getInt("timeout_seconds"),
                rs.getObject("created_by", Long.class),
                rs.getObject("updated_by", Long.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getInt("version"));
    }
}
