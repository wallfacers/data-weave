package com.dataweave.master.companion.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.dataweave.master.companion.domain.CompanionMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code companion_message} 表 JdbcTemplate 仓储——管家会话消息。
 *
 * <p>两种会话：{@code reportId=null}→全局会话（{@code report_id IS NULL}）；非空→锚定该汇报的上下文会话。
 * 发言者身份服务端认定（actor/actor_name 由 controller 注入，仓储只落库）。
 * 历史查询走 {@code idx_companion_message_session (project_id, report_id, created_at)}。
 */
@Repository
public class JdbcCompanionMessageRepository {

    private final JdbcTemplate jdbc;

    public JdbcCompanionMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 落库一条消息（USER/AGENT/SYSTEM）。返回自增 id。 */
    public long insert(long tenantId, long projectId, Long reportId, String role, String actor,
                       String actorName, String content, String brainSessionId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO companion_message (tenant_id, project_id, report_id, role, actor, " +
                    "actor_name, content, brain_session_id, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            int i = 0;
            ps.setLong(++i, tenantId);
            ps.setLong(++i, projectId);
            if (reportId != null) ps.setLong(++i, reportId);
            else ps.setNull(++i, java.sql.Types.BIGINT);
            ps.setString(++i, role);
            ps.setString(++i, actor);
            ps.setString(++i, actorName);
            ps.setString(++i, content);
            ps.setString(++i, brainSessionId);
            ps.setObject(++i, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("companion_message INSERT 未返回主键");
        return key.longValue();
    }

    public CompanionMessage findById(long id) {
        List<CompanionMessage> rows = jdbc.query("SELECT * FROM companion_message WHERE id = ?", (rs, n) -> map(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 历史消息（全局或锚定会话）。{@code reportId=null}→全局（report_id IS NULL）；非空→锚定。
     * {@code before}（可空）=游标（created_at &lt; before），向前翻页。
     *
     * <p>M5：先 {@code DESC LIMIT} 取<b>最新</b> N 条（此前 ASC+LIMIT 取最老 N 条，超 limit 后最新消息永远取不到），
     * 再内存反转为升序返回，对外仍呈现时间正序。
     */
    public List<CompanionMessage> findByProject(long tenantId, long projectId, Long reportId,
                                                LocalDateTime before, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM companion_message WHERE tenant_id = ? AND project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(projectId);
        if (reportId == null) {
            sql.append(" AND report_id IS NULL");
        } else {
            sql.append(" AND report_id = ?");
            args.add(reportId);
        }
        if (before != null) {
            sql.append(" AND created_at < ?");
            args.add(before);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");   // M5：最新 N 条
        args.add(limit);
        List<CompanionMessage> rows = jdbc.query(sql.toString(), (rs, n) -> map(rs), args.toArray());
        java.util.Collections.reverse(rows);   // 反转为升序，对外时间正序
        return rows;
    }

    /**
     * SSE Last-Event-ID 续传：项目内全部消息（全局+锚定）中 id &gt; afterId 的，按 id 升序。
     * 配合 snapshot（重连即全量状态/汇报），补齐离线期间落库的会话消息。
     */
    public List<CompanionMessage> findAfterId(long tenantId, long projectId, long afterId, int limit) {
        return jdbc.query(
                "SELECT * FROM companion_message WHERE tenant_id = ? AND project_id = ? AND id > ? " +
                "ORDER BY id ASC LIMIT ?",
                (rs, n) -> map(rs), tenantId, projectId, afterId, limit);
    }

    /** 最近一条 AGENT 消息的 brain_session_id（同会话续聊复用 workhorse session）；无则 empty。 */
    public Optional<String> findLatestBrainSession(long tenantId, long projectId, Long reportId) {
        String filter = reportId == null ? " AND report_id IS NULL" : " AND report_id = ?";
        String sql = "SELECT brain_session_id FROM companion_message " +
                "WHERE tenant_id = ? AND project_id = ?" + filter +
                " AND role = 'AGENT' AND brain_session_id IS NOT NULL " +
                "ORDER BY created_at DESC, id DESC LIMIT 1";
        List<String> rows;
        if (reportId == null) {
            rows = jdbc.queryForList(sql, String.class, tenantId, projectId);
        } else {
            rows = jdbc.queryForList(sql, String.class, tenantId, projectId, reportId);
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private CompanionMessage map(ResultSet rs) throws SQLException {
        return new CompanionMessage(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getLong("project_id"),
                rs.getObject("report_id", Long.class),
                rs.getString("role"),
                rs.getString("actor"),
                rs.getString("actor_name"),
                rs.getString("content"),
                rs.getString("brain_session_id"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
