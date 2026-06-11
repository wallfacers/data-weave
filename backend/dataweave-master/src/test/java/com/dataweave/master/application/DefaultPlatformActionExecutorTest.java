package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.TaskDiagnosis;
import com.dataweave.master.domain.TaskDiagnosisRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkerNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * applyFix 四动作执行（缺口②）：RERUN / MIGRATE_NODE / RERUN_MORE_MEMORY / CAP_NODE_WEIGHT，
 * 含诊断置 RESOLVED；以及 NODE_EXEC 网关缺失路径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultPlatformActionExecutorTest {

    @Mock
    private TaskInstanceRepository instanceRepository;
    @Mock
    private TaskDiagnosisRepository diagnosisRepository;
    @Mock
    private FleetService fleetService;
    @Mock
    private TaskService taskService;
    @Mock
    private ObjectProvider<NodeExecGateway> nodeExecGateway;
    @Mock
    private WorkflowTriggerService triggerService;
    @Mock
    private RecoveryService recoveryService;
    @Mock
    private com.dataweave.master.domain.WorkflowDefRepository workflowDefRepository;

    private DefaultPlatformActionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DefaultPlatformActionExecutor(instanceRepository, diagnosisRepository,
                fleetService, taskService, nodeExecGateway, triggerService, recoveryService, workflowDefRepository);
        when(instanceRepository.save(any(TaskInstance.class))).thenAnswer(inv -> {
            TaskInstance t = inv.getArgument(0);
            t.setId(java.util.UUID.fromString("01910000-0010-7000-8000-000000000088"));
            return t;
        });
    }

    private AgentAction fixAction(String type) {
        AgentAction a = new AgentAction();
        a.setActionType(type);
        a.setTargetType("DIAGNOSIS");
        a.setTargetId("7");
        return a;
    }

    private TaskDiagnosis diagnosis() {
        TaskDiagnosis d = new TaskDiagnosis();
        d.setId(7L);
        d.setTaskId(10L);
        d.setWorkerNodeCode("node-3");
        return d;
    }

    @Test
    void rerun_producesInstanceAndResolves() {
        when(diagnosisRepository.findById(7L)).thenReturn(Optional.of(diagnosis()));
        var out = executor.execute(fixAction("APPLY_FIX_RERUN"));
        assertThat(out.success()).isTrue();
        assertThat(out.resultInstanceId()).isEqualTo(java.util.UUID.fromString("01910000-0010-7000-8000-000000000088"));
        verifyResolved();
    }

    @Test
    void migrateNode_picksLeastLoadedAndResolves() {
        when(diagnosisRepository.findById(7L)).thenReturn(Optional.of(diagnosis()));
        WorkerNode idle = new WorkerNode();
        idle.setNodeCode("node-9");
        when(fleetService.pickLeastLoadedOnline()).thenReturn(Optional.of(idle));

        var out = executor.execute(fixAction("APPLY_FIX_MIGRATE_NODE"));
        assertThat(out.success()).isTrue();
        assertThat(out.message()).contains("node-9");
        assertThat(out.resultInstanceId()).isEqualTo(java.util.UUID.fromString("01910000-0010-7000-8000-000000000088"));
        verifyResolved();
    }

    @Test
    void rerunMoreMemory_resolves() {
        when(diagnosisRepository.findById(7L)).thenReturn(Optional.of(diagnosis()));
        var out = executor.execute(fixAction("APPLY_FIX_RERUN_MORE_MEMORY"));
        assertThat(out.success()).isTrue();
        assertThat(out.message()).contains("内存");
        verifyResolved();
    }

    @Test
    void capNodeWeight_resolvesNoInstance() {
        when(diagnosisRepository.findById(7L)).thenReturn(Optional.of(diagnosis()));
        var out = executor.execute(fixAction("APPLY_FIX_CAP_NODE_WEIGHT"));
        assertThat(out.success()).isTrue();
        assertThat(out.resultInstanceId()).isNull();
        assertThat(out.message()).contains("权重");
        verifyResolved();
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

    private void verifyResolved() {
        var cap = org.mockito.ArgumentCaptor.forClass(TaskDiagnosis.class);
        verify(diagnosisRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("RESOLVED");
    }
}
