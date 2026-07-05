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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 048 {@code SchedulerKernel.batchCrossCycleReady} 批量跨周期就绪判定:一次 JOIN 依赖 + 一次 COUNT,
 * Java 层组装 readyIds(消 N+1 ①②)。覆盖非 CRON 直通 / 无依赖 / 就绪 / 未就绪 / 首周期豁免 /
 * LAST_DAY 偏移 / 多 dep / 批量混合。
 *
 * <p>参照 {@code SlotManagerDistributedIT}:嵌入式 H2 + 手动建表 + 直接构造(反射调 private 方法)。
 */
class SchedulerKernelBatchCrossCycleTest {

    private JdbcTemplate jdbc;
    private SchedulerKernel kernel;

    @BeforeEach
    void setUp() {
        DataSource ds = new SingleConnectionDataSource("jdbc:h2:mem:bcc_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", true);
        jdbc = new JdbcTemplate(ds);
        try { jdbc.execute("DROP ALL OBJECTS"); } catch (Exception ignored) {}
        jdbc.execute("""
                CREATE TABLE task_instance (
                    workflow_node_id BIGINT,
                    biz_date VARCHAR(20),
                    state VARCHAR(20),
                    deleted INT DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE workflow_dependency (
                    workflow_id BIGINT,
                    node_id BIGINT,
                    depend_node_id BIGINT,
                    date_offset VARCHAR(20),
                    earliest_biz_date VARCHAR(20),
                    enabled INT DEFAULT 1,
                    deleted INT DEFAULT 0
                )
                """);
        kernel = new SchedulerKernel(jdbc,
                mock(InstanceStateMachine.class), mock(SlotManager.class),
                mock(SchedulingPolicy.class), mock(TaskExecutionGateway.class),
                mock(EventBus.class), mock(PreemptionService.class),
                mock(SchedulerMetrics.class), mock(ParallelDispatcher.class),
                mock(ScheduleParamResolver.class), mock(Messages.class),
                mock(PlatformTransactionManager.class), 50, 120, 200);
    }

    private SchedulerKernel.Row row(UUID id, String trigger, long wfId, long nodeId, String bizDate) {
        SchedulerKernel.Row r = new SchedulerKernel.Row();
        r.id = id;
        r.workflowTrigger = trigger;
        r.workflowInstanceId = UUID.randomUUID();  // 非空过条件门
        r.workflowId = wfId;
        r.workflowNodeId = nodeId;
        r.bizDate = bizDate;
        return r;
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> batchCrossCycleReady(List<SchedulerKernel.Row> rows) throws Exception {
        Method m = SchedulerKernel.class.getDeclaredMethod("batchCrossCycleReady", List.class);
        m.setAccessible(true);
        return (Set<UUID>) m.invoke(kernel, rows);
    }

    private void addDep(long wfId, long nodeId, long dependNodeId, String offset, String earliest) {
        jdbc.update("INSERT INTO workflow_dependency (workflow_id, node_id, depend_node_id, date_offset, earliest_biz_date, enabled, deleted) "
                + "VALUES (?,?,?,?,?,1,0)", wfId, nodeId, dependNodeId, offset, earliest);
    }

    private void addSuccess(long nodeId, String bizDate) {
        jdbc.update("INSERT INTO task_instance (workflow_node_id, biz_date, state, deleted) VALUES (?,?,'SUCCESS',0)", nodeId, bizDate);
    }

    @Test
    void nonCron_passThroughReady() throws Exception {
        UUID id = UUID.randomUUID();
        assertThat(batchCrossCycleReady(List.of(row(id, "MANUAL", 1L, 10L, "2026-07-05")))).contains(id);
    }

    @Test
    void cronNoDeps_ready() throws Exception {
        UUID id = UUID.randomUUID();
        assertThat(batchCrossCycleReady(List.of(row(id, "CRON", 1L, 10L, "2026-07-05")))).contains(id);
    }

    @Test
    void cronWithReadyDeps_ready() throws Exception {
        addDep(1L, 10L, 99L, "LAST_DAY", "2026-01-01");
        addSuccess(99L, "2026-07-04");  // LAST_DAY → prevBizDate = bizDate-1
        UUID id = UUID.randomUUID();
        assertThat(batchCrossCycleReady(List.of(row(id, "CRON", 1L, 10L, "2026-07-05")))).contains(id);
    }

    @Test
    void cronWithUnreadyDeps_notReady() throws Exception {
        addDep(1L, 10L, 99L, "LAST_DAY", "2026-01-01");
        // 不插 node 99 的 2026-07-04 SUCCESS
        UUID id = UUID.randomUUID();
        assertThat(batchCrossCycleReady(List.of(row(id, "CRON", 1L, 10L, "2026-07-05")))).doesNotContain(id);
    }

    @Test
    void cronFirstCycleExempt_ready() throws Exception {
        // earliest 晚于 bizDate → 首周期豁免,不查 COUNT
        addDep(1L, 10L, 99L, "LAST_DAY", "2026-12-31");
        UUID id = UUID.randomUUID();
        assertThat(batchCrossCycleReady(List.of(row(id, "CRON", 1L, 10L, "2026-07-05")))).contains(id);
    }

    @Test
    void cronLastDayOffset_correctPrevDate() throws Exception {
        addDep(1L, 10L, 99L, "LAST_DAY", "2026-01-01");
        addSuccess(99L, "2026-07-04");  // 正确 prevDate(bizDate-1)
        UUID id = UUID.randomUUID();
        assertThat(batchCrossCycleReady(List.of(row(id, "CRON", 1L, 10L, "2026-07-05")))).contains(id);
    }

    @Test
    void cronLastDayOffset_wrongPrevDate_notReady() throws Exception {
        addDep(1L, 10L, 99L, "LAST_DAY", "2026-01-01");
        addSuccess(99L, "2026-07-03");  // 错误(需 07-04)
        UUID id = UUID.randomUUID();
        assertThat(batchCrossCycleReady(List.of(row(id, "CRON", 1L, 10L, "2026-07-05")))).doesNotContain(id);
    }

    @Test
    void multipleDeps_partialUnready_notReady() throws Exception {
        addDep(1L, 10L, 99L, "CURRENT_DAY", "2026-01-01");
        addDep(1L, 10L, 88L, "CURRENT_DAY", "2026-01-01");
        addSuccess(99L, "2026-07-05");  // 仅 dep1 就绪
        UUID id = UUID.randomUUID();
        assertThat(batchCrossCycleReady(List.of(row(id, "CRON", 1L, 10L, "2026-07-05")))).doesNotContain(id);
    }

    @Test
    void multipleDeps_allReady_ready() throws Exception {
        addDep(1L, 10L, 99L, "CURRENT_DAY", "2026-01-01");
        addDep(1L, 10L, 88L, "CURRENT_DAY", "2026-01-01");
        addSuccess(99L, "2026-07-05");
        addSuccess(88L, "2026-07-05");
        UUID id = UUID.randomUUID();
        assertThat(batchCrossCycleReady(List.of(row(id, "CRON", 1L, 10L, "2026-07-05")))).contains(id);
    }

    @Test
    void batchMultipleRows_mixedReadiness() throws Exception {
        addDep(1L, 10L, 99L, "LAST_DAY", "2026-01-01");
        addSuccess(99L, "2026-07-04");
        addDep(1L, 11L, 77L, "LAST_DAY", "2026-01-01");  // node 77 未就绪(无 SUCCESS)
        UUID ready = UUID.randomUUID();
        UUID unready = UUID.randomUUID();
        Set<UUID> result = batchCrossCycleReady(List.of(
                row(ready, "CRON", 1L, 10L, "2026-07-05"),
                row(unready, "CRON", 1L, 11L, "2026-07-05")));
        assertThat(result).contains(ready);
        assertThat(result).doesNotContain(unready);
    }
}
