package com.dataweave.master.infrastructure;

import com.dataweave.master.domain.Checkpoint;
import com.dataweave.master.domain.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 062 {@code task_checkpoint} 的 JdbcTemplate 仓储。
 *
 * <p>只做纯持久化 + 查询，不含业务重试/终态副作用（那些在 {@code CheckpointService}）。滚动淘汰的
 * 「保留最近 N 个」策略由服务层调用 {@link #nextOrdinal}/{@link #insert}/{@link #expireBeyond} 组合实现。
 */
@Repository
public class CheckpointRepository {

    private final JdbcTemplate jdbc;

    public CheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Checkpoint> MAPPER = (rs, n) -> new Checkpoint(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("task_instance_id"),
            rs.getInt("ordinal"),
            rs.getString("checkpoint_path"),
            rs.getString("external_ref"),
            rs.getString("status"),
            (Long) rs.getObject("size_bytes"),
            rs.getObject("completed_at", LocalDateTime.class),
            rs.getObject("created_at", LocalDateTime.class));

    /** 插入一条检查点并返回其 id（UUIDv7 应用层生成）。 */
    public UUID insert(UUID taskInstanceId, int ordinal, String checkpointPath, String externalRef,
                       String status, Long sizeBytes, LocalDateTime completedAt) {
        UUID id = Uuid7.generate();
        jdbc.update(
                "INSERT INTO task_checkpoint (id, task_instance_id, ordinal, checkpoint_path, external_ref, "
                        + "status, size_bytes, completed_at, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, taskInstanceId, ordinal, checkpointPath, externalRef, status, sizeBytes,
                completedAt != null ? Timestamp.valueOf(completedAt) : null,
                Timestamp.valueOf(LocalDateTime.now()));
        return id;
    }

    /** 该实例的检查点列表（ordinal DESC，面板/续跑选择用）。 */
    public List<Checkpoint> listByInstance(UUID taskInstanceId) {
        return jdbc.query(
                "SELECT * FROM task_checkpoint WHERE task_instance_id=? ORDER BY ordinal DESC",
                MAPPER, taskInstanceId);
    }

    public Optional<Checkpoint> findById(UUID id) {
        List<Checkpoint> rows = jdbc.query("SELECT * FROM task_checkpoint WHERE id=?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 该实例最近一个 SUCCESS 检查点（面板 lastCheckpoint 用）。 */
    public Optional<Checkpoint> findLatestSuccess(UUID taskInstanceId) {
        List<Checkpoint> rows = jdbc.query(
                "SELECT * FROM task_checkpoint WHERE task_instance_id=? AND status='SUCCESS' "
                        + "ORDER BY ordinal DESC LIMIT 1",
                MAPPER, taskInstanceId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 下一个 ordinal（当前实例内最大 ordinal + 1，无则 1）。 */
    public int nextOrdinal(UUID taskInstanceId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(ordinal), 0) FROM task_checkpoint WHERE task_instance_id=?",
                Integer.class, taskInstanceId);
        return (max == null ? 0 : max) + 1;
    }

    public int countByInstance(UUID taskInstanceId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_checkpoint WHERE task_instance_id=?", Integer.class, taskInstanceId);
        return c == null ? 0 : c;
    }

    /** 是否存在 IN_PROGRESS 检查点（并发触发 savepoint 防护）。 */
    public boolean hasInProgress(UUID taskInstanceId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_checkpoint WHERE task_instance_id=? AND status='IN_PROGRESS'",
                Integer.class, taskInstanceId);
        return c != null && c > 0;
    }

    /**
     * 滚动淘汰：把该实例中 SUCCESS 且 ordinal 不在「最大 N 个」之内的检查点标记 EXPIRED（软淘汰，供审计）。
     * 返回被淘汰的行数。
     */
    public int expireBeyond(UUID taskInstanceId, int retain) {
        return jdbc.update(
                "UPDATE task_checkpoint SET status='EXPIRED' WHERE task_instance_id=? AND status='SUCCESS' "
                        + "AND ordinal NOT IN (SELECT ordinal FROM task_checkpoint WHERE task_instance_id=? "
                        + "AND status='SUCCESS' ORDER BY ordinal DESC LIMIT ?)",
                taskInstanceId, taskInstanceId, retain);
    }
}
