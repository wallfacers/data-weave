package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.i18n.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 049-收尾 {@code SchedulerKernel.collectReady} 游标翻窗防饿死:上游就绪门移到 Java 后,
 * 窗口(claimCandidateSize)可能被上游未就绪的老实例占满,窗口外真正就绪的实例饿死——
 * 整窗无够量就绪时按 (updated_at,id) 元组游标续扫,claimMaxWindows 有界。
 *
 * <p>参照 {@code SchedulerKernelBatchUpstreamTest}(049):嵌入式 H2 + 手动建表 + 反射调 private 方法。
 * H2 MODE=PostgreSQL 支持 FOR UPDATE SKIP LOCKED(api h2 套件全程在跑同一 SQL)。
 */
class SchedulerKernelCollectReadyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 6, 12, 0, 0);

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource ds = new SingleConnectionDataSource("jdbc:h2:mem:collect_ready_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", true);
        jdbc = new JdbcTemplate(ds);
        try { jdbc.execute("DROP ALL OBJECTS"); } catch (Exception ignored) {}
        jdbc.execute("""
                CREATE TABLE task_instance (
                    id UUID PRIMARY KEY,
                    workflow_instance_id UUID,
                    workflow_node_id BIGINT,
                    task_id BIGINT,
                    task_version_no INT,
                    content_override VARCHAR(4000),
                    params_override VARCHAR(4000),
                    attempt INT,
                    run_mode VARCHAR(20),
                    biz_date VARCHAR(20),
                    updated_at TIMESTAMP,
                    type_override VARCHAR(40),
                    locale VARCHAR(10),
                    state VARCHAR(20),
                    deleted INT DEFAULT 0,
                    backfill_held INT DEFAULT 0
                )
                """);
        jdbc.execute("CREATE TABLE workflow_instance (id UUID PRIMARY KEY, priority INT, state VARCHAR(20), "
                + "trigger_type VARCHAR(20), workflow_id BIGINT)");
        jdbc.execute("CREATE TABLE task_def (id BIGINT PRIMARY KEY, timeout_sec INT, type VARCHAR(40), datasource_id BIGINT)");
        jdbc.execute("CREATE TABLE workflow_edge (to_node_id BIGINT, from_node_id BIGINT, strength VARCHAR(20), deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE workflow_dependency (workflow_id BIGINT, node_id BIGINT, depend_node_id BIGINT, "
                + "date_offset VARCHAR(20), earliest_biz_date VARCHAR(20), enabled INT, deleted INT DEFAULT 0)");
    }

    /** claimBatchSize=2 / claimCandidateSize=3(小窗放大饿死场景)/ maxWindows 可调。 */
    private SchedulerKernel kernel(int maxWindows) {
        return new SchedulerKernel(jdbc,
                mock(InstanceStateMachine.class), mock(SlotManager.class),
                mock(SchedulingPolicy.class), mock(TaskExecutionGateway.class),
                mock(EventBus.class), mock(PreemptionService.class),
                mock(SchedulerMetrics.class), mock(ParallelDispatcher.class),
                mock(ScheduleParamResolver.class), mock(Messages.class),
                mock(PlatformTransactionManager.class), 2, 120, 3, maxWindows, false);
    }

    @SuppressWarnings("unchecked")
    private List<SchedulerKernel.Row> collectReady(SchedulerKernel kernel, SchedulerKernel.RunMode mode) throws Exception {
        Method m = SchedulerKernel.class.getDeclaredMethod("collectReady", SchedulerKernel.RunMode.class, List.class);
        m.setAccessible(true);
        List<SchedulerKernel.Row> ready = new ArrayList<>();
        m.invoke(kernel, mode, ready);
        return ready;
    }

    /** 上游未就绪的 NORMAL 实例:MANUAL wf + 未完成上游 edge(pred 无 SUCCESS 行)。 */
    private UUID insertBlocked(UUID wi, long nodeId, LocalDateTime updatedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance (id, workflow_instance_id, workflow_node_id, state, run_mode, "
                        + "updated_at, deleted, backfill_held) VALUES (?,?,?,'WAITING','NORMAL',?,0,0)",
                id, wi, nodeId, updatedAt);
        return id;
    }

    /** 就绪的 NORMAL 实例:MANUAL wf,无上游 edge → 直通。 */
    private UUID insertReady(UUID wi, long nodeId, LocalDateTime updatedAt) {
        return insertBlocked(wi, nodeId, updatedAt);  // 就绪与否由 edge 决定,行本身同构
    }

    private UUID insertWf(long workflowId) {
        UUID wi = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_instance (id, priority, state, trigger_type, workflow_id) "
                + "VALUES (?,5,'RUNNING','MANUAL',?)", wi, workflowId);
        return wi;
    }

    @Test
    void starvedReadyRow_beyondFirstWindow_isCollectedViaCursor() throws Exception {
        // 前 3 个(=claimCandidateSize 整窗)老实例全部上游未就绪:node 11/12/13 依赖 node 99(无 SUCCESS pred)
        UUID wiBlocked = insertWf(1L);
        for (long node = 11; node <= 13; node++) {
            jdbc.update("INSERT INTO workflow_edge (to_node_id, from_node_id, strength, deleted) VALUES (?,99,'STRONG',0)", node);
            insertBlocked(wiBlocked, node, BASE.plusSeconds(node));
        }
        // 窗口外(更新时间更晚)的就绪实例:独立 wf,无上游 edge
        UUID wiReady = insertWf(2L);
        UUID readyId = insertReady(wiReady, 21L, BASE.plusSeconds(100));

        List<SchedulerKernel.Row> ready = collectReady(kernel(5), SchedulerKernel.RunMode.NORMAL);

        assertThat(ready).extracting(r -> r.id).containsExactly(readyId);
    }

    @Test
    void maxWindowsCap_boundsScan_starvedRowNotFoundBeyondCap() throws Exception {
        // 6 个未就绪老实例(2 整窗)+ 1 个就绪实例在第 3 窗;maxWindows=2 → 扫不到(有界护栏生效)
        UUID wiBlocked = insertWf(1L);
        for (long node = 11; node <= 16; node++) {
            jdbc.update("INSERT INTO workflow_edge (to_node_id, from_node_id, strength, deleted) VALUES (?,99,'STRONG',0)", node);
            insertBlocked(wiBlocked, node, BASE.plusSeconds(node));
        }
        UUID wiReady = insertWf(2L);
        insertReady(wiReady, 21L, BASE.plusSeconds(100));

        List<SchedulerKernel.Row> capped = collectReady(kernel(2), SchedulerKernel.RunMode.NORMAL);
        assertThat(capped).isEmpty();

        // 同数据放宽到 3 窗 → 找到
        List<SchedulerKernel.Row> found = collectReady(kernel(3), SchedulerKernel.RunMode.NORMAL);
        assertThat(found).hasSize(1);
    }

    @Test
    void firstWindowHasEnoughReady_noExtraWindow() throws Exception {
        // 首窗就绪量 ≥ claimBatchSize(2) → 不续窗(热路径与 049 原形态一致)
        UUID wi = insertWf(1L);
        UUID r1 = insertReady(wi, 11L, BASE.plusSeconds(1));
        UUID r2 = insertReady(wi, 12L, BASE.plusSeconds(2));
        insertReady(wi, 13L, BASE.plusSeconds(3));

        List<SchedulerKernel.Row> ready = collectReady(kernel(5), SchedulerKernel.RunMode.NORMAL);
        assertThat(ready).extracting(r -> r.id).contains(r1, r2);
    }

    @Test
    void sameUpdatedAt_cursorTupleAdvances_noInfiniteRescan() throws Exception {
        // 批量物化常共享同一 updated_at:4 个未就绪 + 1 个就绪全部同刻 → (updated_at,id) 决胜键必须推进。
        // 固定 UUID 保证就绪行排在 id 序最后(必在首窗之外),确定性逼出游标续扫。
        UUID wiBlocked = insertWf(1L);
        for (long node = 11; node <= 14; node++) {
            jdbc.update("INSERT INTO workflow_edge (to_node_id, from_node_id, strength, deleted) VALUES (?,99,'STRONG',0)", node);
            UUID blockedId = UUID.fromString("00000000-0000-0000-0000-0000000000" + node);
            jdbc.update("INSERT INTO task_instance (id, workflow_instance_id, workflow_node_id, state, run_mode, "
                            + "updated_at, deleted, backfill_held) VALUES (?,?,?,'WAITING','NORMAL',?,0,0)",
                    blockedId, wiBlocked, node, BASE);
        }
        UUID wiReady = insertWf(2L);
        // 7fff… 在 H2(带符号 long 比较)与 PG(无符号字节序)下都排在 0000… 之后 → 跨方言确定性
        UUID readyId = UUID.fromString("7fffffff-ffff-ffff-7fff-ffffffffffff");
        jdbc.update("INSERT INTO task_instance (id, workflow_instance_id, workflow_node_id, state, run_mode, "
                        + "updated_at, deleted, backfill_held) VALUES (?,?,21,'WAITING','NORMAL',?,0,0)",
                readyId, wiReady, BASE);  // 同一时间戳

        List<SchedulerKernel.Row> ready = collectReady(kernel(5), SchedulerKernel.RunMode.NORMAL);
        assertThat(ready).extracting(r -> r.id).containsExactly(readyId);
    }
}
