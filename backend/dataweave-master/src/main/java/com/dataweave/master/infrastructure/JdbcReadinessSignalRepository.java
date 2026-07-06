package com.dataweave.master.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 051 就绪态物化：readiness_signal 表 JDBC 实现。
 *
 * <p>写入用 GeneratedKeyHolder 取自增主键（H2/PG 通用，勿用 H2 旧 CALL IDENTITY()）。
 * <p>消费用 FOR UPDATE SKIP LOCKED 批量领取（多 master 各领各的，不重复推进）。
 */
@Repository
public class JdbcReadinessSignalRepository implements ReadinessSignalRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcReadinessSignalRepository.class);

    private final JdbcTemplate jdbc;

    public JdbcReadinessSignalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(ReadinessSignalRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO readiness_signal (tenant_id, project_id, kind, upstream_instance_id, " +
                    "workflow_id, workflow_instance_id, workflow_node_id, biz_date, " +
                    "processed, created_at) VALUES (?,?,?,?,?,?,?,?,0,?)",
                    new String[]{"id"});
            int i = 0;
            ps.setLong(++i, row.tenantId());
            ps.setLong(++i, row.projectId());
            ps.setString(++i, row.kind());
            ps.setObject(++i, row.upstreamInstanceId());
            if (row.workflowId() != null) ps.setLong(++i, row.workflowId());
            else ps.setNull(++i, java.sql.Types.BIGINT);
            if (row.workflowInstanceId() != null) ps.setObject(++i, row.workflowInstanceId());
            else ps.setNull(++i, java.sql.Types.OTHER);
            if (row.workflowNodeId() != null) ps.setLong(++i, row.workflowNodeId());
            else ps.setNull(++i, java.sql.Types.BIGINT);
            if (row.bizDate() != null) ps.setString(++i, row.bizDate());
            else ps.setNull(++i, java.sql.Types.VARCHAR);
            ps.setTimestamp(++i, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new IllegalStateException("readiness_signal INSERT 未返回主键");
        }
        long id = generatedId.longValue();
        log.debug("[ReadinessSignal] INSERT id={} kind={} upstream={}", id, row.kind(), row.upstreamInstanceId());
        return id;
    }

    @Override
    public List<ReadinessSignalRow> pollPending(int limit) {
        String sql = "SELECT id, tenant_id, project_id, kind, upstream_instance_id, " +
                "workflow_id, workflow_instance_id, workflow_node_id, biz_date, " +
                "processed, created_at, processed_at " +
                "FROM readiness_signal WHERE processed = 0 " +
                "ORDER BY id ASC LIMIT ? FOR UPDATE SKIP LOCKED";
        List<ReadinessSignalRow> rows = jdbc.query(sql, (rs, n) -> {
            long id = rs.getLong("id");
            long tenantId = rs.getLong("tenant_id");
            long projectId = rs.getLong("project_id");
            String kind = rs.getString("kind");
            UUID upstreamInstanceId = rs.getObject("upstream_instance_id", UUID.class);
            Long workflowId = (Long) rs.getObject("workflow_id");
            UUID workflowInstanceId = rs.getObject("workflow_instance_id", UUID.class);
            Long workflowNodeId = (Long) rs.getObject("workflow_node_id");
            String bizDate = rs.getString("biz_date");
            int processed = rs.getInt("processed");
            Timestamp createdAtTs = rs.getTimestamp("created_at");
            LocalDateTime createdAt = createdAtTs != null ? createdAtTs.toLocalDateTime() : null;
            Timestamp processedAtTs = rs.getTimestamp("processed_at");
            LocalDateTime processedAt = processedAtTs != null ? processedAtTs.toLocalDateTime() : null;
            return new ReadinessSignalRow(id, tenantId, projectId, kind, upstreamInstanceId,
                    workflowId, workflowInstanceId, workflowNodeId, bizDate,
                    processed, createdAt, processedAt);
        }, limit);
        return rows != null ? rows : Collections.emptyList();
    }

    @Override
    public void markProcessed(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        // 构建 IN 子句（批量标记，与 poll 同事务）
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.valueOf(LocalDateTime.now()));
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) in.append(',');
            in.append('?');
            args.add(ids.get(i));
        }
        String sql = "UPDATE readiness_signal SET processed = 1, processed_at = ? WHERE id IN (" + in + ")";
        int updated = jdbc.update(sql, args.toArray());
        log.debug("[ReadinessSignal] markProcessed {} / {} ids", updated, ids.size());
    }

    /** 未处理信号积压深度（供 metrics gauge）。 */
    public long countPending() {
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM readiness_signal WHERE processed = 0", Long.class);
        return cnt != null ? cnt : 0;
    }
}
