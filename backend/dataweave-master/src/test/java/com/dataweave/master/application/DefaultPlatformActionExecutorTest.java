package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.i18n.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台动作执行：TASK_RERUN、NODE_EXEC 网关缺失路径、工作流实例生命周期（kill/pause/resume）等。
 * 诊断/修复动作（APPLY_FIX_*）已在 Weft AI 拆除中随 TaskDiagnosis 移除。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultPlatformActionExecutorTest {

    @Mock
    private TaskInstanceRepository instanceRepository;
    @Mock
    private FleetService fleetService;
    @Mock
    private TaskService taskService;
    @Mock
    private WorkflowService workflowService;
    @Mock
    private ObjectProvider<NodeExecGateway> nodeExecGateway;
    @Mock
    private WorkflowTriggerService triggerService;
    @Mock
    private RecoveryService recoveryService;
    @Mock
    private com.dataweave.master.domain.WorkflowDefRepository workflowDefRepository;
    @Mock
    private OpsService opsService;
    @Mock
    private ObjectProvider<OpsService> opsServiceProvider;
    @Mock
    private ObjectProvider<ProjectSyncService> projectSyncServiceProvider;
    @Mock
    private ObjectProvider<com.dataweave.master.application.lineage.LineageCorrectionService> lineageCorrectionServiceProvider;
    @Mock
    private ObjectProvider<BackfillService> backfillServiceProvider;
    @Mock
    private com.dataweave.master.domain.TaskDefRepository taskDefRepository;
    @Mock
    private com.dataweave.master.infrastructure.CheckpointRepository checkpointRepository;
    @Mock
    private com.dataweave.master.infrastructure.incident.IncidentRepository incidentRepository;
    @Mock
    private com.dataweave.master.infrastructure.incident.IncidentProposalRepository incidentProposalRepository;
    @Mock
    private com.dataweave.master.infrastructure.incident.IncidentMessageRepository incidentMessageRepository;
    @Mock
    private com.dataweave.master.application.incident.IncidentEventPublisher incidentEventPublisher;

    private DefaultPlatformActionExecutor executor;

    private Messages realMessages() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return new Messages(ms);
    }

    @BeforeEach
    void setUp() {
        when(opsServiceProvider.getObject()).thenReturn(opsService);
        executor = new DefaultPlatformActionExecutor(instanceRepository,
                fleetService, taskService, workflowService, nodeExecGateway, triggerService, recoveryService, workflowDefRepository,
                opsServiceProvider, projectSyncServiceProvider, java.util.List.of(),
                lineageCorrectionServiceProvider, backfillServiceProvider, realMessages(),
                taskDefRepository, checkpointRepository, incidentRepository, incidentProposalRepository,
                incidentMessageRepository, incidentEventPublisher);
        when(instanceRepository.save(any(TaskInstance.class))).thenAnswer(inv -> {
            TaskInstance t = inv.getArgument(0);
            t.setId(java.util.UUID.fromString("01910000-0010-7000-8000-000000000088"));
            return t;
        });
    }

    @Test
    void taskRerun_loadsInstanceAndProducesNew() {
        TaskInstance src = new TaskInstance();
        src.setId(java.util.UUID.fromString("01910000-0010-7000-8000-000000000100"));
        src.setTaskId(10L);
        src.setWorkerNodeCode("node-1");
        when(instanceRepository.findById(java.util.UUID.fromString("01910000-0010-7000-8000-000000000100"))).thenReturn(Optional.of(src));

        AgentAction a = new AgentAction();
        a.setActionType("TASK_RERUN");
        a.setTargetType("TASK_INSTANCE");
        a.setTargetId("01910000-0010-7000-8000-000000000100");

        var out = executor.execute(a);
        assertThat(out.success()).isTrue();
        assertThat(out.resultInstanceId()).isEqualTo(java.util.UUID.fromString("01910000-0010-7000-8000-000000000088"));
    }

    @Test
    void nodeExec_gatewayAbsent_returnsClearError() {
        when(nodeExecGateway.getIfAvailable()).thenReturn(null);
        AgentAction a = new AgentAction();
        a.setActionType("NODE_EXEC");
        a.setTargetType("NODE");
        a.setTargetId("node-1");
        a.setCommand("df -h");

        var out = executor.execute(a);
        assertThat(out.success()).isFalse();
        assertThat(out.message()).contains("未接线");
    }

    @Test
    void unsupportedAction_returnsClearError() {
        AgentAction a = new AgentAction();
        a.setActionType("UNKNOWN_ACTION");
        var out = executor.execute(a);
        assertThat(out.success()).isFalse();
        assertThat(out.message()).contains("UNKNOWN_ACTION");
    }

    private AgentAction instanceAction(String type, String instanceId) {
        AgentAction a = new AgentAction();
        a.setActionType(type);
        a.setTargetType("WORKFLOW_INSTANCE");
        a.setTargetId(instanceId);
        return a;
    }

    @Test
    void killInstance_success_callsOpsAndReportsState() {
        java.util.UUID id = java.util.UUID.fromString("01910000-0003-7000-8000-000000000003");
        com.dataweave.master.domain.WorkflowInstance wi = new com.dataweave.master.domain.WorkflowInstance();
        wi.setId(id);
        wi.setState("STOPPED");
        when(opsService.killWorkflow(id)).thenReturn(wi);

        var out = executor.execute(instanceAction("KILL_INSTANCE", id.toString()));

        assertThat(out.success()).isTrue();
        assertThat(out.message()).contains("STOPPED");
        assertThat(out.resultInstanceId()).isEqualTo(id);
        verify(opsService).killWorkflow(id);
    }

    @Test
    void pauseInstance_success_callsOps() {
        java.util.UUID id = java.util.UUID.fromString("01910000-0003-7000-8000-000000000003");
        com.dataweave.master.domain.WorkflowInstance wi = new com.dataweave.master.domain.WorkflowInstance();
        wi.setId(id);
        wi.setState("PAUSED");
        when(opsService.pauseWorkflow(id)).thenReturn(wi);

        var out = executor.execute(instanceAction("PAUSE_INSTANCE", id.toString()));

        assertThat(out.success()).isTrue();
        assertThat(out.message()).contains("PAUSED");
        verify(opsService).pauseWorkflow(id);
    }

    @Test
    void resumeInstance_success_callsOps() {
        java.util.UUID id = java.util.UUID.fromString("01910000-0003-7000-8000-000000000003");
        com.dataweave.master.domain.WorkflowInstance wi = new com.dataweave.master.domain.WorkflowInstance();
        wi.setId(id);
        wi.setState("RUNNING");
        when(opsService.resumeWorkflow(id)).thenReturn(wi);

        var out = executor.execute(instanceAction("RESUME_INSTANCE", id.toString()));

        assertThat(out.success()).isTrue();
        assertThat(out.message()).contains("RUNNING");
        verify(opsService).resumeWorkflow(id);
    }

    @Test
    void killInstance_terminalState_returnsClearFailureNotThrow() {
        java.util.UUID id = java.util.UUID.fromString("01910000-0003-7000-8000-000000000003");
        when(opsService.killWorkflow(id)).thenThrow(new IllegalStateException("Cannot kill a terminal instance"));

        var out = executor.execute(instanceAction("KILL_INSTANCE", id.toString()));

        assertThat(out.success()).isFalse();
        assertThat(out.message()).contains("terminal");
        assertThat(out.resultInstanceId()).isNull();
    }

    @Test
    void instanceOp_badUuid_returnsClearError() {
        var out = executor.execute(instanceAction("KILL_INSTANCE", "not-a-uuid"));
        assertThat(out.success()).isFalse();
        assertThat(out.message()).contains("非法");
    }

    // ---- 067 T023: incident_publish_fix ----

    private com.dataweave.master.domain.incident.IncidentProposal proposal(
            java.util.UUID id, java.util.UUID incidentId, long taskDefId, int baseVersionNo, Long agentActionId, String status) {
        return new com.dataweave.master.domain.incident.IncidentProposal(
                id, incidentId, taskDefId, baseVersionNo, "select 2 -- fixed", "fixed typo", "{}",
                status, agentActionId, null, null, null, null, null, null);
    }

    private com.dataweave.master.domain.TaskDef taskDef(long id, int currentVersionNo) {
        com.dataweave.master.domain.TaskDef t = new com.dataweave.master.domain.TaskDef();
        t.setId(id);
        t.setName("demo-task");
        t.setType("SQL");
        t.setContent("select 1");
        t.setCurrentVersionNo(currentVersionNo);
        return t;
    }

    private AgentAction publishFixAction(java.util.UUID proposalId, java.util.UUID incidentId) {
        AgentAction a = new AgentAction();
        a.setId(900L);
        a.setActionType("INCIDENT_PUBLISH_FIX");
        a.setTargetType("INCIDENT_PROPOSAL");
        a.setTargetId(proposalId.toString());
        a.setCommand("{\"incidentId\":\"" + incidentId + "\"}");
        return a;
    }

    @Test
    void incidentPublishFix_staleBaseline_marksStaleAndEscalates() {
        java.util.UUID proposalId = java.util.UUID.randomUUID();
        java.util.UUID incidentId = java.util.UUID.randomUUID();
        when(incidentProposalRepository.findById(proposalId))
                .thenReturn(Optional.of(proposal(proposalId, incidentId, 42L, 1, 900L, "PENDING")));
        when(taskDefRepository.findById(42L)).thenReturn(Optional.of(taskDef(42L, 2))); // 当前 v2 != baseline v1

        var out = executor.execute(publishFixAction(proposalId, incidentId));

        assertThat(out.success()).isFalse();
        verify(incidentProposalRepository).casStatus(proposalId, "PENDING", "STALE");
        verify(incidentRepository).casState(incidentId, "AWAITING_APPROVAL", "NEEDS_HUMAN");
        verify(taskDefRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void incidentPublishFix_taskDeleted_escalates() {
        java.util.UUID proposalId = java.util.UUID.randomUUID();
        java.util.UUID incidentId = java.util.UUID.randomUUID();
        when(incidentProposalRepository.findById(proposalId))
                .thenReturn(Optional.of(proposal(proposalId, incidentId, 42L, 1, 900L, "PENDING")));
        when(taskDefRepository.findById(42L)).thenReturn(Optional.empty());

        var out = executor.execute(publishFixAction(proposalId, incidentId));

        assertThat(out.success()).isFalse();
        verify(incidentRepository).casState(incidentId, "AWAITING_APPROVAL", "NEEDS_HUMAN");
    }

    @Test
    void incidentPublishFix_success_publishesSnapshotAndReruns() {
        java.util.UUID proposalId = java.util.UUID.randomUUID();
        java.util.UUID incidentId = java.util.UUID.randomUUID();
        java.util.UUID latestInstanceId = java.util.UUID.randomUUID();
        com.dataweave.master.domain.TaskDef task = taskDef(42L, 1);
        when(incidentProposalRepository.findById(proposalId))
                .thenReturn(Optional.of(proposal(proposalId, incidentId, 42L, 1, 900L, "PENDING")));
        when(taskDefRepository.findById(42L)).thenReturn(Optional.of(task));
        when(taskService.writeTaskVersionSnapshot(eq(task), org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(2);

        com.dataweave.master.domain.incident.Incident inc = new com.dataweave.master.domain.incident.Incident(
                incidentId, 1L, 1L, 42L, "demo-task", latestInstanceId, latestInstanceId, 1,
                "CRON", "CODE", "HIGH", "AWAITING_APPROVAL", null, 1, "summary", null, null,
                null, null, 0, null, null);
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(inc));
        when(incidentRepository.casState(incidentId, "AWAITING_APPROVAL", "ACTING")).thenReturn(true);
        TaskInstance rerun = new TaskInstance();
        rerun.setId(latestInstanceId);
        rerun.setState("WAITING");
        when(opsService.rerunInstance(latestInstanceId)).thenReturn(rerun);

        var out = executor.execute(publishFixAction(proposalId, incidentId));

        assertThat(out.success()).isTrue();
        assertThat(out.resultInstanceId()).isEqualTo(latestInstanceId);
        assertThat(task.getContent()).isEqualTo("select 2 -- fixed");
        verify(taskDefRepository).save(task);
        verify(incidentProposalRepository).markPublished(proposalId, 2);
        verify(incidentRepository).casState(incidentId, "AWAITING_APPROVAL", "ACTING");
        verify(opsService).rerunInstance(latestInstanceId);
    }
}
