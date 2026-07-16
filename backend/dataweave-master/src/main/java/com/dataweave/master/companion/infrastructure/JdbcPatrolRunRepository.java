package com.dataweave.master.companion.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.dataweave.master.companion.domain.PatrolRun;
import com.dataweave.master.companion.domain.PatrolRunStates;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code patrol_run} 表 JdbcTemplate 仓储——巡检调度的状态地基。
 *
 * <p>双阶段调度（守调度不变量①-④）：
 * <ul>
 *   <li><b>arm</b>：{@link #tryClaimCreate} 抄 cron_fire——多 master 都 INSERT，撞
 *       {@code UNIQUE(routine_id, scheduled_fire_time)} 键者放弃（不变量① 的幂等单赢形态）。</li>
 *   <li><b>claim</b>：{@link #pollClaimed} 用 {@code FOR UPDATE SKIP LOCKED} 认领 CLAIMED 行（不变量①）；
 *       {@link #casStart}/{@link #casFinish} CAS 推进（不变量②）。brain 外呼在调用方事务外（不变量④）。</li>
 * </ul>
 */
@Repository
public class JdbcPatrolRunRepository {

    private final JdbcTemplate jdbc;

    public JdbcPatrolRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * arm 阶段：幂等单赢创建 CLAIMED run。{@code UNIQUE(routine_id, scheduled_fire_time)} 冲突
     * = 本触发点已被别的 master 创建，返回 empty（调用方放弃）。
     */
    public Optional<PatrolRun> tryClaimCreate(long tenantId, long projectId, long routineId, String triggerType,
                                              LocalDateTime scheduledFireTime) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                // 仅写显式提供的列：state/started_at/finished_at/summary/error/created_at/updated_at/version
                // 全部走 DDL 默认值（state=CLAIMED，version=0，时间=CURRENT_TIMESTAMP，其余 NULL）
                var ps = con.prepareStatement(
                        "INSERT INTO patrol_run (tenant_id, project_id, routine_id, trigger_type, scheduled_fire_time) " +
                        "VALUES (?,?,?,?,?)",
                        new String[]{"id"});
                int i = 0;
                ps.setLong(++i, tenantId);
                ps.setLong(++i, projectId);
                ps.setLong(++i, routineId);
                ps.setString(++i, triggerType);
                ps.setObject(++i, scheduledFireTime);
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("patrol_run INSERT 未返回主键");
        return findById(key.longValue());
    }

    /**
     * claim 阶段：批量领取 CLAIMED run（不变量① SKIP LOCKED）。最早计划触发时刻优先。
     * 调用方需在同一事务内调 {@link #casStart} 推进至 RUNNING，事务提交后才在事务外 brain 外呼。
     */
    public List<PatrolRun> pollClaimed(int limit) {
        String sql = "SELECT * FROM patrol_run WHERE state = ? " +
                "ORDER BY scheduled_fire_time ASC, id ASC LIMIT ? FOR UPDATE SKIP LOCKED";
        List<PatrolRun> rows = jdbc.query(sql, (rs, n) -> map(rs), PatrolRunStates.CLAIMED, limit);
        return rows != null ? rows : Collections.emptyList();
    }

    /** claim→执行：CAS CLAIMED→RUNNING + 落 started_at（不变量②，事务内持久化）。 */
    public boolean casStart(long id) {
        int rows = jdbc.update(
                "UPDATE patrol_run SET state = ?, started_at = ?, updated_at = ?, version = version + 1 " +
                "WHERE id = ? AND state = ?",
                PatrolRunStates.RUNNING, LocalDateTime.now(), LocalDateTime.now(), id, PatrolRunStates.CLAIMED);
        return rows == 1;
    }

    /** 执行→终态：CAS RUNNING→{SUCCEEDED|FAILED} + finished_at + summary/error（不变量②）。 */
    public boolean casFinish(long id, String toState, String summary, String error) {
        int rows = jdbc.update(
                "UPDATE patrol_run SET state = ?, finished_at = ?, summary = ?, error = ?, " +
                "updated_at = ?, version = version + 1 WHERE id = ? AND state = ?",
                toState, LocalDateTime.now(), summary, error, LocalDateTime.now(), id, PatrolRunStates.RUNNING);
        return rows == 1;
    }

    /** reaper：CAS RUNNING→TIMEOUT + finished_at + error（超时兜底，与 casFinish 同族单赢）。 */
    public boolean markTimeout(long id, String error) {
        int rows = jdbc.update(
                "UPDATE patrol_run SET state = ?, finished_at = ?, error = ?, updated_at = ?, version = version + 1 " +
                "WHERE id = ? AND state = ?",
                PatrolRunStates.TIMEOUT, LocalDateTime.now(), error, LocalDateTime.now(), id, PatrolRunStates.RUNNING);
        return rows == 1;
    }

    public Optional<PatrolRun> findById(long id) {
        List<PatrolRun> list = jdbc.query("SELECT * FROM patrol_run WHERE id = ?", (rs, n) -> map(rs), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** US4 执行历史：按 routine 取最近 N 轮（触发时间/耗时/结论/关联汇报）。 */
    public List<PatrolRun> findByRoutine(long routineId, int limit) {
        return jdbc.query(
                "SELECT * FROM patrol_run WHERE routine_id = ? ORDER BY scheduled_fire_time DESC, id DESC LIMIT ?",
                (rs, n) -> map(rs), routineId, limit);
    }

    /** arm 阶段：例程已 arm 的最近触发时刻（max scheduled_fire_time）；无则 empty。 */
    public Optional<LocalDateTime> findLastFireTime(long routineId) {
        List<LocalDateTime> rows = jdbc.queryForList(
                "SELECT MAX(scheduled_fire_time) FROM patrol_run WHERE routine_id = ?",
                LocalDateTime.class, routineId);
        return (rows == null || rows.isEmpty() || rows.get(0) == null)
                ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 概况"今日巡检轮次"：今日创建的 run 数（任意状态）。 */
    public int countToday(long tenantId, long projectId, LocalDateTime todayStart) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM patrol_run WHERE tenant_id = ? AND project_id = ? AND created_at >= ?",
                Integer.class, tenantId, projectId, todayStart);
        return c == null ? 0 : c;
    }

    /** 状态归一"patrol"形态：项目内是否存在 RUNNING 的 run。 */
    public boolean existsRunningInProject(long tenantId, long projectId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM patrol_run WHERE tenant_id = ? AND project_id = ? AND state = ?",
                Integer.class, tenantId, projectId, PatrolRunStates.RUNNING);
        return c != null && c > 0;
    }

    /**
     * reaper 候选：全部 RUNNING run + 其例程超时阈值（跨租户扫描惯例）。调用方在 Java 里算
     * {@code started_at + timeoutSeconds < now} 判超时（避 PG INTERVAL / H2 方言差异，同 TimeoutSweeper）。
     */
    public List<RunningRun> findRunningCandidates() {
        return jdbc.query(
                "SELECT pr.id, pr.tenant_id, pr.project_id, pr.routine_id, pr.started_at, rt.timeout_seconds " +
                "FROM patrol_run pr JOIN patrol_routine rt ON rt.id = pr.routine_id " +
                "WHERE pr.state = ? AND pr.started_at IS NOT NULL",
                (rs, n) -> new RunningRun(
                        rs.getLong("id"),
                        rs.getLong("tenant_id"),
                        rs.getLong("project_id"),
                        rs.getLong("routine_id"),
                        rs.getObject("started_at", LocalDateTime.class),
                        rs.getInt("timeout_seconds")),
                PatrolRunStates.RUNNING);
    }

    /** reaper 候选轻量记录（run + 例程超时）。 */
    public record RunningRun(long id, long tenantId, long projectId, long routineId,
                             LocalDateTime startedAt, int timeoutSeconds) {}

    private PatrolRun map(ResultSet rs) throws SQLException {
        return new PatrolRun(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getLong("project_id"),
                rs.getLong("routine_id"),
                rs.getString("trigger_type"),
                rs.getObject("scheduled_fire_time", LocalDateTime.class),
                rs.getString("state"),
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class),
                rs.getString("summary"),
                rs.getString("error"),
                rs.getInt("version"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }
}
