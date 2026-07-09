package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * T027: TimeoutSweeper 超时扫除测试。
 *
 * <p>验证：RUNNING 超 timeout_sec → FAILED(TIMEOUT)；未超时不扫；未配置 timeout_sec 不扫。
 * 使用嵌入式 H2（同 InstanceStateMachineTest 范式）。
 */
class TimeoutSweeperTest {

    private JdbcTemplate jdbc;
    private InstanceStateMachine sm;
    private TimeoutSweeper sweeper;
    /** 返回当前时间，供测试插入相对时间（sweeper 内部用 LocalDateTime.now() 计算 elapsed）。 */
    private static LocalDateTime now() { return LocalDateTime.now(); }

    @BeforeEach
    void setUp() {
        DataSource ds = new SingleConnectionDataSource(
                "jdbc:h2:mem:ts_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", true);
        jdbc = new JdbcTemplate(ds);
        // task_instance 精简表
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS task_instance (
                    id UUID PRIMARY KEY,
                    task_id BIGINT,
                    task_version_no INT,
                    state VARCHAR(32),
                    started_at TIMESTAMP,
                    finished_at TIMESTAMP,
                    failure_reason VARCHAR(64),
                    updated_at TIMESTAMP,
                    deleted INT DEFAULT 0,
                    tenant_id BIGINT DEFAULT 1
                )
                """);
        // task_def 精简表（含 timeout_sec）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS task_def (
                    id BIGINT PRIMARY KEY,
                    timeout_sec INT
                )
                """);
        // task_def_version 精简表
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS task_def_version (
                    task_id BIGINT,
                    version_no INT,
                    timeout_sec INT
                )
                """);
        jdbc.update("DELETE FROM task_instance");
        jdbc.update("DELETE FROM task_def");
        jdbc.update("DELETE FROM task_def_version");

        EventBus eventBus = mock(EventBus.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        sm = new InstanceStateMachine(jdbc, eventBus, eventPublisher);
        sweeper = new TimeoutSweeper(jdbc, sm, eventBus, eventPublisher);
    }

    /** 插入 task_def 并返回 id */
    private long insertTaskDef(int timeoutSec) {
        jdbc.update("INSERT INTO task_def (id, timeout_sec) VALUES (1, ?)", timeoutSec);
        return 1L;
    }

    /** 插入 RUNNING 实例（task_id=1, started_at=指定时间） */
    private UUID insertRunning(long taskId, LocalDateTime startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO task_instance (id, task_id, state, started_at, deleted, tenant_id) VALUES (?, ?, 'RUNNING', ?, 0, 1)",
                id, taskId, startedAt);
        return id;
    }

    /** 插入 RUNNING 实例（指定 task_version_no，走 task_def_version 的 timeout_sec） */
    private UUID insertRunningWithVersion(long taskId, int versionNo, LocalDateTime startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO task_instance (id, task_id, task_version_no, state, started_at, deleted, tenant_id) VALUES (?, ?, ?, 'RUNNING', ?, 0, 1)",
                id, taskId, versionNo, startedAt);
        return id;
    }

    private String stateOf(UUID id) {
        return jdbc.queryForObject("SELECT state FROM task_instance WHERE id=?", String.class, id);
    }

    private String failureReasonOf(UUID id) {
        return jdbc.queryForObject(
                "SELECT failure_reason FROM task_instance WHERE id=?", String.class, id);
    }

    // ─── T027 测试用例 ───────────────────────────────────────

    @Test
    void sweep_timeoutInstance_markedFailedTimeout() {
        long taskId = insertTaskDef(30); // timeout_sec=30
        UUID id = insertRunning(taskId, now().minusSeconds(120)); // started 120s ago → 超时

        sweeper.sweep();

        assertThat(stateOf(id)).isEqualTo("FAILED");
        assertThat(failureReasonOf(id)).isEqualTo("TIMEOUT");
    }

    @Test
    void sweep_notYetTimeout_staysRunning() {
        long taskId = insertTaskDef(300); // timeout_sec=300
        UUID id = insertRunning(taskId, now().minusSeconds(5)); // started 5s ago → 未超

        sweeper.sweep();

        assertThat(stateOf(id)).isEqualTo("RUNNING");
    }

    @Test
    void sweep_noTimeoutConfigured_skipped() {
        // 不插入 task_def（无 timeout_sec 配置）
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO task_instance (id, task_id, state, started_at, deleted, tenant_id) VALUES (?, 999, 'RUNNING', ?, 0, 1)",
                id, now().minusSeconds(3600));

        sweeper.sweep();

        // LEFT JOIN 返回 NULL timeout_sec → WHERE 条件排除 → 不扫
        assertThat(stateOf(id)).isEqualTo("RUNNING");
    }

    @Test
    void sweep_timeoutZero_skipped() {
        long taskId = insertTaskDef(0); // timeout_sec=0 视为不限时
        UUID id = insertRunning(taskId, now().minusSeconds(3600));

        sweeper.sweep();

        assertThat(stateOf(id)).isEqualTo("RUNNING");
    }

    @Test
    void sweep_notRunning_skipped() {
        long taskId = insertTaskDef(10);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO task_instance (id, task_id, state, started_at, deleted, tenant_id) VALUES (?, ?, 'DISPATCHED', ?, 0, 1)",
                id, taskId, now().minusSeconds(60));

        sweeper.sweep();

        // DISPATCHED 不被扫（WHERE state='RUNNING'）
        assertThat(stateOf(id)).isEqualTo("DISPATCHED");
    }

    @Test
    void sweep_startedAtNull_skipped() {
        long taskId = insertTaskDef(10);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO task_instance (id, task_id, state, started_at, deleted, tenant_id) VALUES (?, ?, 'RUNNING', NULL, 0, 1)",
                id, taskId);

        sweeper.sweep();

        // started_at IS NULL → WHERE 排除 → 不扫
        assertThat(stateOf(id)).isEqualTo("RUNNING");
    }

    @Test
    void sweep_usesVersionTimeoutWhenAvailable() {
        long taskId = insertTaskDef(60); // task_def.timeout_sec=60
        // task_def_version.timeout_sec=10（版本快照更短）
        jdbc.update("INSERT INTO task_def_version (task_id, version_no, timeout_sec) VALUES (?, 1, ?)", taskId, 10);
        UUID id = insertRunningWithVersion(taskId, 1, now().minusSeconds(120)); // started 120s ago > 10

        sweeper.sweep();

        // 应使用 version 的 timeout_sec=10 → 超时
        assertThat(stateOf(id)).isEqualTo("FAILED");
        assertThat(failureReasonOf(id)).isEqualTo("TIMEOUT");
    }

    @Test
    void sweep_alreadyTerminal_notTouched() {
        long taskId = insertTaskDef(10);
        LocalDateTime ts = now();
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO task_instance (id, task_id, state, started_at, deleted, tenant_id, failure_reason, finished_at) VALUES (?, ?, 'FAILED', ?, 0, 1, 'EXIT_CODE_1', ?)",
                id, taskId, ts.minusSeconds(60), ts);

        sweeper.sweep();

        // casTaskTerminalFromActive WHERE state IN ('DISPATCHED','RUNNING') → FAILED 不匹配 → 跳过
        assertThat(stateOf(id)).isEqualTo("FAILED");
        assertThat(failureReasonOf(id)).isEqualTo("EXIT_CODE_1");
    }

    @Test
    void sweep_multipleTimeoutInstances_allMarkedFailed() {
        long taskId = insertTaskDef(20);
        UUID id1 = insertRunning(taskId, now().minusSeconds(120));
        UUID id2 = insertRunning(taskId, now().minusSeconds(90));
        UUID id3 = insertRunning(taskId, now().minusSeconds(5)); // 未超时

        sweeper.sweep();

        assertThat(stateOf(id1)).isEqualTo("FAILED");
        assertThat(stateOf(id2)).isEqualTo("FAILED");
        assertThat(stateOf(id3)).isEqualTo("RUNNING"); // not yet
    }
}
