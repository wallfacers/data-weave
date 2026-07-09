package com.dataweave.master.application;

import com.dataweave.master.domain.*;
import com.dataweave.master.i18n.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpsService 新增方法单元测试（Mockito，无 DB）：
 * queryWorkflowInstances / getInstanceDag / resolveActualCode / resolveActualConfig。
 */
class OpsServiceWorkflowInstanceTest {

    private TaskInstanceRepository instanceRepository;
    private JdbcTemplate jdbc;
    private OpsService ops;

    @BeforeEach
    void setUp() {
        instanceRepository = mock(TaskInstanceRepository.class);
        TaskDefRepository taskDefRepository = mock(TaskDefRepository.class);
        WorkflowInstanceRepository workflowInstanceRepository = mock(WorkflowInstanceRepository.class);
        WorkflowDefRepository workflowDefRepository = mock(WorkflowDefRepository.class);
        InstanceStateMachine stateMachine = mock(InstanceStateMachine.class);
        WorkflowStateService workflowStateService = mock(WorkflowStateService.class);
        LogBus logBus = mock(LogBus.class);
        EventBus eventBus = mock(EventBus.class);
        jdbc = mock(JdbcTemplate.class);
        // Weft A 已拆除服务端 AI（DiagnosisService 移除）；OpsService 末位参为 origin 的 AgentActionRepository
        ops = new OpsService(taskDefRepository, instanceRepository, workflowInstanceRepository,
                workflowDefRepository, stateMachine, workflowStateService, logBus, eventBus, jdbc,
                mock(AgentActionRepository.class));
    }

    // ─── queryWorkflowInstances ─────────────────────────────

    @Test
    void queryWorkflowInstancesReturnsPageResult() {
        OpsContracts.WorkflowInstanceQuery q = new OpsContracts.WorkflowInstanceQuery(
                "RUNNING", null, "CRON", null, null, null, null, null, null, null, null, null, null, null, 0, 20, null);

        when(jdbc.queryForObject(startsWith("SELECT COUNT(*)"), eq(Long.class), any(Object[].class)))
                .thenReturn(5L);
        when(jdbc.query(contains("FROM workflow_instance wi"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        var result = ops.queryWorkflowInstances(q);
        assertThat(result).isNotNull();
        assertThat(result.total()).isEqualTo(5L);
        assertThat(result.items()).isEmpty();
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
    }

    @Test
    void queryWorkflowInstancesClampsPageSize() {
        OpsContracts.WorkflowInstanceQuery q = new OpsContracts.WorkflowInstanceQuery(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, -1, 500, null);

        when(jdbc.queryForObject(startsWith("SELECT COUNT(*)"), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        when(jdbc.query(contains("FROM workflow_instance wi"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        var result = ops.queryWorkflowInstances(q);
        // size clamped to [1, 200], page clamped to >=0
        assertThat(result.size()).isLessThanOrEqualTo(200);
    }

    // ─── getInstanceDag ─────────────────────────────────────

    @Test
    void getInstanceDagThrowsWhenWorkflowInstanceNotFound() {
        UUID nonExistent = UUID.randomUUID();
        when(jdbc.query(contains("FROM workflow_instance wi"), any(RowMapper.class), eq(nonExistent)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> ops.getInstanceDag(nonExistent))
                .isInstanceOf(BizException.class);
    }

    // ─── DTO 构造验证 ──────────────────────────────────────

    @Test
    void workflowInstanceQueryFiltersAreNullable() {
        // 全部筛选为空时应可正常构造
        var q = new OpsContracts.WorkflowInstanceQuery(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0, 10, null);
        assertThat(q.page()).isEqualTo(0);
        assertThat(q.size()).isEqualTo(10);
        assertThat(q.state()).isNull();
        assertThat(q.triggerType()).isNull();
    }

    @Test
    void instanceDagNodeDtosAreWellFormed() {
        var node = new OpsContracts.InstanceDagNode("node_1", "test_task", 1L, UUID.randomUUID(),
                "SUCCESS", 0, "2026-06-26T09:00:00", "2026-06-26T09:01:00", 60000L,
                100.0, 200.0, "TASK");
        assertThat(node.nodeKey()).isEqualTo("node_1");
        assertThat(node.state()).isEqualTo("SUCCESS");
        assertThat(node.durationMs()).isEqualTo(60000L);
    }

    @Test
    void instanceDagEdgeDtosAreWellFormed() {
        var edge = new OpsContracts.InstanceDagEdge("from", "to", "STRONG");
        assertThat(edge.fromNodeKey()).isEqualTo("from");
        assertThat(edge.toNodeKey()).isEqualTo("to");
        assertThat(edge.strength()).isEqualTo("STRONG");
    }

    @Test
    void resolvedCodeViewIsWellFormed() {
        var view = new OpsContracts.ResolvedCodeView(UUID.randomUUID(),
                "echo ${date}", "echo 20260626", List.of(), "NORMAL", false, "SHELL");
        assertThat(view.rawContent()).isEqualTo("echo ${date}");
        assertThat(view.resolvedContent()).isEqualTo("echo 20260626");
        assertThat(view.isOverride()).isFalse();
        assertThat(view.taskType()).isEqualTo("SHELL");
    }

    @Test
    void resolvedConfigViewIncludesOverrideInfo() {
        var view = new OpsContracts.ResolvedConfigView(UUID.randomUUID(),
                "SHELL", 300, "FIXED(3,60s)", "cpu=2,mem=1024Mi",
                "{}", "{}", List.of(), "NORMAL", false, null, null, 1);
        assertThat(view.timeoutSeconds()).isEqualTo(300);
        assertThat(view.retryStrategy()).isEqualTo("FIXED(3,60s)");
        assertThat(view.isOverride()).isFalse();
    }
}
