package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 048 {@link InstanceStateMachine#casDispatchBatch} 批量下发 CAS:单 SQL {@code UPDATE FROM VALUES}
 * (H2/PG 双兼容,无 RETURNING,research R1/R2)。验证批量 CAS 语义 + 各字段正确 + 非 WAITING 跳过(CAS 让步)。
 *
 * <p>参照 {@code SlotManagerDistributedIT}:嵌入式 H2 + 手动建表,不依赖 @SpringBootTest。
 */
class InstanceStateMachineTest {

    private JdbcTemplate jdbc;
    private InstanceStateMachine sm;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 5, 12, 0, 0);

    @BeforeEach
    void setUp() {
        DataSource ds = new SingleConnectionDataSource("jdbc:h2:mem:ism_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS task_instance (
                    id UUID PRIMARY KEY,
                    state VARCHAR(20),
                    worker_node_code VARCHAR(50),
                    lease_expire_at TIMESTAMP,
                    attempt INT,
                    updated_at TIMESTAMP,
                    deleted INT DEFAULT 0,
                    workflow_instance_id UUID
                )
                """);
        jdbc.update("DELETE FROM task_instance");  // 共享内存库(ism_it)跨 test 清残留
        sm = new InstanceStateMachine(jdbc, mock(EventBus.class), mock(ApplicationEventPublisher.class));
    }

    private UUID insertWaiting() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance (id, state, deleted) VALUES (?, 'WAITING', 0)", id);
        return id;
    }

    @Test
    void casDispatchBatch_allWaitings_updatesAllAndReturnsCount() {
        UUID u1 = insertWaiting(), u2 = insertWaiting(), u3 = insertWaiting();
        LocalDateTime lease = NOW.plusSeconds(60);
        List<InstanceStateMachine.DispatchPlacement> placements = List.of(
                new InstanceStateMachine.DispatchPlacement(u1, "w1", lease, 1),
                new InstanceStateMachine.DispatchPlacement(u2, "w2", lease, 2),
                new InstanceStateMachine.DispatchPlacement(u3, "w3", lease, 3));
        int count = sm.casDispatchBatch(placements, NOW);
        assertThat(count).isEqualTo(3);
        assertThat(stateOf(u1)).isEqualTo("DISPATCHED");
        assertThat(stateOf(u2)).isEqualTo("DISPATCHED");
        assertThat(stateOf(u3)).isEqualTo("DISPATCHED");
        assertThat(workerOf(u1)).isEqualTo("w1");
        assertThat(attemptOf(u2)).isEqualTo(2);
    }

    @Test
    void casDispatchBatch_skipsNonWaiting_casSemantics() {
        // 仅 WAITING 行被推进;DISPATCHED 行 WHERE state='WAITING' 不匹配 → CAS 让步(updateCount 不含它)
        UUID waiting = insertWaiting();
        UUID dispatched = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance (id, state, deleted) VALUES (?, 'DISPATCHED', 0)", dispatched);
        LocalDateTime lease = NOW.plusSeconds(60);
        List<InstanceStateMachine.DispatchPlacement> placements = List.of(
                new InstanceStateMachine.DispatchPlacement(waiting, "w1", lease, 1),
                new InstanceStateMachine.DispatchPlacement(dispatched, "w2", lease, 5));
        int count = sm.casDispatchBatch(placements, NOW);
        assertThat(count).isEqualTo(1);
        assertThat(stateOf(waiting)).isEqualTo("DISPATCHED");
        assertThat(attemptOf(dispatched)).isNull();  // 原 DISPATCHED 行 attempt 未被覆盖
    }

    @Test
    void casDispatchBatch_empty_isNoop() {
        int count = sm.casDispatchBatch(List.of(), NOW);
        assertThat(count).isZero();
    }

    @Test
    void casDispatchBatch_singleRow_correctFields() {
        UUID u = insertWaiting();
        LocalDateTime lease = NOW.plusSeconds(120);
        int count = sm.casDispatchBatch(List.of(
                new InstanceStateMachine.DispatchPlacement(u, "wx", lease, 7)), NOW);
        assertThat(count).isEqualTo(1);
        assertThat(stateOf(u)).isEqualTo("DISPATCHED");
        assertThat(workerOf(u)).isEqualTo("wx");
        assertThat(attemptOf(u)).isEqualTo(7);
    }

    // ─── 收尾:滞留下发守卫 + 事务后事件发布 ─────────────────

    @Test
    void isCurrentDispatch_matchesStateAndAttempt() {
        UUID u = insertWaiting();
        sm.casDispatchBatch(List.of(
                new InstanceStateMachine.DispatchPlacement(u, "w1", NOW.plusSeconds(60), 2)), NOW);
        assertThat(sm.isCurrentDispatch(u, 2)).isTrue();
        assertThat(sm.isCurrentDispatch(u, 1)).isFalse();  // 旧 attempt 滞留命令(DB attempt 已推进到 2)→ 拒发
    }

    @Test
    void isCurrentDispatch_notSupersededEvenIfNotYetDispatched_true() {
        // 正向判定陈旧修复:异步下发线程可能抢在认领事务提交可见之前读库(读到 pre-claim 的 WAITING/低 attempt
        // 快照)。只要 DB attempt 未被推进到严格更高(未被 LeaseReaper 回收重派),就是当前派单 → 照发。
        // 原实现要求 state='DISPATCHED' 才认账 → 读到 WAITING 即假阴性丢首投,是 ~2 分钟调度延迟的根因。
        UUID freshWaiting = insertWaiting();  // attempt NULL(pre-claim 快照)
        assertThat(sm.isCurrentDispatch(freshWaiting, 1)).isTrue();

        UUID sameAttempt = UUID.randomUUID();  // 已提交 DISPATCHED attempt=1(正常首投)
        jdbc.update("INSERT INTO task_instance (id, state, attempt, deleted) VALUES (?, 'DISPATCHED', 1, 0)", sameAttempt);
        assertThat(sm.isCurrentDispatch(sameAttempt, 1)).isTrue();
    }

    @Test
    void isCurrentDispatch_supersededOrMissing_false() {
        UUID superseded = UUID.randomUUID();  // 已被回收重派:DB attempt=2 > 命令 attempt=1
        jdbc.update("INSERT INTO task_instance (id, state, attempt, deleted) VALUES (?, 'DISPATCHED', 2, 0)", superseded);
        assertThat(sm.isCurrentDispatch(superseded, 1)).isFalse();
        assertThat(sm.isCurrentDispatch(UUID.randomUUID(), 1)).isFalse();  // 不存在 → 拒发(不下发幽灵)
    }

    @Test
    void publishDispatchedEvents_publishesPerWorkflowChannel_skipsNullWf() {
        EventBus bus = mock(EventBus.class);
        InstanceStateMachine sm2 = new InstanceStateMachine(jdbc, bus, mock(ApplicationEventPublisher.class));
        UUID t1 = UUID.randomUUID(), wf1 = UUID.randomUUID(), t2 = UUID.randomUUID();
        sm2.publishDispatchedEvents(List.of(
                new InstanceStateMachine.DispatchedEvent(t1, wf1),
                new InstanceStateMachine.DispatchedEvent(t2, null)));  // 单跑实例无通道,跳过
        verify(bus).publish("dw:evt:" + wf1, "{\"taskId\":\"" + t1 + "\",\"taskState\":\"DISPATCHED\"}");
        verifyNoMoreInteractions(bus);
    }

    private String stateOf(UUID id) {
        return jdbc.queryForObject("SELECT state FROM task_instance WHERE id=?", String.class, id);
    }
    private String workerOf(UUID id) {
        return jdbc.queryForObject("SELECT worker_node_code FROM task_instance WHERE id=?", String.class, id);
    }
    private Integer attemptOf(UUID id) {
        return jdbc.queryForObject("SELECT attempt FROM task_instance WHERE id=?", Integer.class, id);
    }
}
