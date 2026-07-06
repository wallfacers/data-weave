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
 * 049 {@code SchedulerKernel.batchUpstreamReady} 批量上游就绪判定(替 selectRunnable NOT EXISTS,
 * 避大表 NOT EXISTS 退化)。覆盖强/弱依赖、pred SUCCESS/FAILED/STOPPED、无 edge 直通、多上游、缺字段直通。
 *
 * <p>参照 {@code SchedulerKernelBatchCrossCycleTest}(048):嵌入式 H2 + 手动建表 + 反射调 private 方法。
 */
class SchedulerKernelBatchUpstreamTest {

    private JdbcTemplate jdbc;
    private SchedulerKernel kernel;

    @BeforeEach
    void setUp() {
        DataSource ds = new SingleConnectionDataSource("jdbc:h2:mem:upstream_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", true);
        jdbc = new JdbcTemplate(ds);
        try { jdbc.execute("DROP ALL OBJECTS"); } catch (Exception ignored) {}
        jdbc.execute("CREATE TABLE task_instance (workflow_instance_id UUID, workflow_node_id BIGINT, state VARCHAR(20), deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE workflow_edge (to_node_id BIGINT, from_node_id BIGINT, strength VARCHAR(20), deleted INT DEFAULT 0)");
        kernel = new SchedulerKernel(jdbc,
                mock(InstanceStateMachine.class), mock(SlotManager.class),
                mock(SchedulingPolicy.class), mock(TaskExecutionGateway.class),
                mock(EventBus.class), mock(PreemptionService.class),
                mock(SchedulerMetrics.class), mock(ParallelDispatcher.class),
                mock(ScheduleParamResolver.class), mock(Messages.class),
                mock(PlatformTransactionManager.class), 50, 120, 200, 5, false);
    }

    private SchedulerKernel.Row row(UUID id, UUID wi, long nodeId) {
        SchedulerKernel.Row r = new SchedulerKernel.Row();
        r.id = id;
        r.workflowInstanceId = wi;
        r.workflowNodeId = nodeId;
        return r;
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> batchUpstreamReady(List<SchedulerKernel.Row> rows) throws Exception {
        Method m = SchedulerKernel.class.getDeclaredMethod("batchUpstreamReady", List.class);
        m.setAccessible(true);
        return (Set<UUID>) m.invoke(kernel, rows);
    }

    private void addEdge(long toNode, long fromNode, String strength) {
        jdbc.update("INSERT INTO workflow_edge (to_node_id, from_node_id, strength, deleted) VALUES (?,?,?,0)",
                toNode, fromNode, strength);
    }

    private void addPred(UUID wi, long node, String state) {
        jdbc.update("INSERT INTO task_instance (workflow_instance_id, workflow_node_id, state, deleted) VALUES (?,?,?,0)",
                wi, node, state);
    }

    @Test
    void noEdge_singleNodeWf_ready() throws Exception {
        UUID id = UUID.randomUUID();
        assertThat(batchUpstreamReady(List.of(row(id, UUID.randomUUID(), 10L)))).contains(id);
    }

    @Test
    void strongDep_predSuccess_ready() throws Exception {
        UUID wi = UUID.randomUUID();
        addEdge(10L, 99L, "STRONG");
        addPred(wi, 99L, "SUCCESS");
        UUID id = UUID.randomUUID();
        assertThat(batchUpstreamReady(List.of(row(id, wi, 10L)))).contains(id);
    }

    @Test
    void strongDep_predFailed_notReady() throws Exception {
        UUID wi = UUID.randomUUID();
        addEdge(10L, 99L, "STRONG");
        addPred(wi, 99L, "FAILED");  // 强依赖 FAILED 不满足
        UUID id = UUID.randomUUID();
        assertThat(batchUpstreamReady(List.of(row(id, wi, 10L)))).doesNotContain(id);
    }

    @Test
    void strongDep_defaultStrength_predSuccess_ready() throws Exception {
        UUID wi = UUID.randomUUID();
        addEdge(10L, 99L, null);  // 默认 STRONG(COALESCE)
        addPred(wi, 99L, "SUCCESS");
        UUID id = UUID.randomUUID();
        assertThat(batchUpstreamReady(List.of(row(id, wi, 10L)))).contains(id);
    }

    @Test
    void weakDep_predSuccess_ready() throws Exception {
        UUID wi = UUID.randomUUID();
        addEdge(10L, 99L, "WEAK");
        addPred(wi, 99L, "SUCCESS");
        UUID id = UUID.randomUUID();
        assertThat(batchUpstreamReady(List.of(row(id, wi, 10L)))).contains(id);
    }

    @Test
    void weakDep_predFailed_ready() throws Exception {
        UUID wi = UUID.randomUUID();
        addEdge(10L, 99L, "WEAK");
        addPred(wi, 99L, "FAILED");  // 弱依赖 FAILED 满足(自然跑完)
        UUID id = UUID.randomUUID();
        assertThat(batchUpstreamReady(List.of(row(id, wi, 10L)))).contains(id);
    }

    @Test
    void weakDep_predStopped_notReady() throws Exception {
        UUID wi = UUID.randomUUID();
        addEdge(10L, 99L, "WEAK");
        addPred(wi, 99L, "STOPPED");  // 中止非跑完;且 STOPPED 不在 state IN ('SUCCESS','FAILED') → 查不到
        UUID id = UUID.randomUUID();
        assertThat(batchUpstreamReady(List.of(row(id, wi, 10L)))).doesNotContain(id);
    }

    @Test
    void multipleUpstreams_allReady_ready() throws Exception {
        UUID wi = UUID.randomUUID();
        addEdge(10L, 99L, "STRONG");
        addEdge(10L, 88L, "WEAK");
        addPred(wi, 99L, "SUCCESS");
        addPred(wi, 88L, "FAILED");  // 弱依赖 FAILED 满足
        UUID id = UUID.randomUUID();
        assertThat(batchUpstreamReady(List.of(row(id, wi, 10L)))).contains(id);
    }

    @Test
    void multipleUpstreams_partialNotReady_notReady() throws Exception {
        UUID wi = UUID.randomUUID();
        addEdge(10L, 99L, "STRONG");
        addEdge(10L, 88L, "STRONG");
        addPred(wi, 99L, "SUCCESS");
        // 88 无 SUCCESS
        UUID id = UUID.randomUUID();
        assertThat(batchUpstreamReady(List.of(row(id, wi, 10L)))).doesNotContain(id);
    }

    @Test
    void missingWorkflowInstanceId_passThrough() throws Exception {
        UUID id = UUID.randomUUID();
        // workflowInstanceId null → 直通(单跑实例无上游门)
        assertThat(batchUpstreamReady(List.of(row(id, null, 10L)))).contains(id);
    }

    @Test
    void batchMultipleRows_mixedReadiness() throws Exception {
        UUID wi = UUID.randomUUID();
        addEdge(10L, 99L, "STRONG");
        addPred(wi, 99L, "SUCCESS");
        addEdge(11L, 77L, "STRONG");  // 77 无 pred → 未就绪
        UUID ready = UUID.randomUUID();
        UUID unready = UUID.randomUUID();
        Set<UUID> result = batchUpstreamReady(List.of(
                row(ready, wi, 10L),
                row(unready, wi, 11L)));
        assertThat(result).contains(ready);
        assertThat(result).doesNotContain(unready);
    }
}
