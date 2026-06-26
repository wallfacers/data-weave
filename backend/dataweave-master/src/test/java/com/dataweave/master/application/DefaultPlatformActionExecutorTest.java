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
                opsServiceProvider, realMessages());
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
}
