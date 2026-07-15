package com.dataweave.master.infrastructure.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.dataweave.master.domain.Uuid7;
import com.dataweave.master.domain.incident.IncidentMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * incident_message 表 JdbcTemplate 仓储。seq 用「读当前最大值+1 插入，唯一约束冲突则重试」
 * 分配（{@code UNIQUE(incident_id, seq)}），避免额外计数表，H2/PG 通用、无需序列对象。
 */
@Repository
public class IncidentMessageRepository {

    private static final int MAX_RETRY = 5;

    private final JdbcTemplate jdbc;

    public IncidentMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条线程消息（Agent/system 路径，无显示名）。 */
    public IncidentMessage append(UUID incidentId, String kind, String content, String payloadJson, String actor) {
        return append(incidentId, kind, content, payloadJson, actor, null);
    }

    /**
     * 追加一条线程消息，事故级递增序号发号。并发冲突自动重试（乐观，事故级写入并发通常很低）。
     * actorName=发言者显示名（人类发言由服务端解析填写；Agent/system 传 null）。
     */
    public IncidentMessage append(UUID incidentId, String kind, String content, String payloadJson,
                                  String actor, String actorName) {
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            long seq = nextSeq(incidentId);
            UUID id = Uuid7.generate();
            LocalDateTime now = LocalDateTime.now();
            try {
                jdbc.update(
                        "INSERT INTO incident_message (id, incident_id, seq, kind, content, payload_json, " +
                        "  actor, actor_name, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                        id, incidentId, seq, kind, content, payloadJson, actor, actorName, now);
                return new IncidentMessage(id, incidentId, seq, kind, content, payloadJson, actor, actorName, now);
            } catch (DuplicateKeyException e) {
                // 并发发号撞车，重试下一个 seq
            }
        }
        throw new IllegalStateException("incident_message seq allocation failed after retries: incidentId=" + incidentId);
    }

    private long nextSeq(UUID incidentId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM incident_message WHERE incident_id = ?",
                Long.class, incidentId);
        return (max == null ? 0L : max) + 1;
    }

    public List<IncidentMessage> findAfter(UUID incidentId, long afterSeq, int limit) {
        return jdbc.query(
                "SELECT * FROM incident_message WHERE incident_id = ? AND seq > ? ORDER BY seq LIMIT ?",
                (rs, n) -> map(rs), incidentId, afterSeq, limit);
    }

    public int countByIncident(UUID incidentId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_message WHERE incident_id = ?", Integer.class, incidentId);
        return c == null ? 0 : c;
    }

    private IncidentMessage map(ResultSet rs) throws SQLException {
        return new IncidentMessage(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("incident_id"),
                rs.getLong("seq"),
                rs.getString("kind"),
                rs.getString("content"),
                rs.getString("payload_json"),
                rs.getString("actor"),
                rs.getString("actor_name"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
