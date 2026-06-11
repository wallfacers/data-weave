package com.dataweave.api;

import com.dataweave.master.application.RecoveryService;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败恢复（task 2.9，H2）：断点恢复保留成功节点、从失败点续跑；整流重跑重置全节点。
 * 经唤醒驱动调度内核续跑至工作流 SUCCESS。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {"scheduler.test-isolation=recovery"})  // 独立 context，隔离其它测试遗留实例
class SchedulerRecoveryTest {

    @Autowired
    RecoveryService recoveryService;
    @Autowired
    WorkflowInstanceRepository workflowInstanceRepository;
    @Autowired
    TaskInstanceRepository taskInstanceRepository;

    /** 基于 workflow_def=1（节点 1→2,1→3）构造一个 FAILED 工作流实例：node1 SUCCESS / node2 FAILED / node3 STOPPED。 */
    private UUID buildFailedWorkflowInstance() {
        LocalDateTime t = LocalDateTime.now();
        WorkflowInstance wi = new WorkflowInstance();
        wi.setTenantId(1L);
        wi.setProjectId(1L);
        wi.setWorkflowId(1L);
        wi.setWorkflowVersionNo(1);
        wi.setTriggerType("MANUAL");
        wi.setState(InstanceStates.FAILED);
        wi.setPriority(5);
        wi.setBizDate("2026-06-11");
        wi.setTotalTasks(3);
        wi.setStartedAt(t);
        wi.setCreatedAt(t);
        wi.setUpdatedAt(t);
        wi.setDeleted(0);
        wi.setVersion(0L);
        UUID wiId = workflowInstanceRepository.save(wi).getId();

        node(wiId, 1L, 2L, InstanceStates.SUCCESS, "node-1");
        node(wiId, 2L, 1L, InstanceStates.FAILED, "node-3");
        node(wiId, 3L, 3L, InstanceStates.STOPPED, null);
        return wiId;
    }

    private void node(UUID wiId, long nodeId, long taskId, String state, String worker) {
        LocalDateTime t = LocalDateTime.now();
        TaskInstance ti = new TaskInstance();
        ti.setTenantId(1L);
        ti.setProjectId(1L);
        ti.setWorkflowInstanceId(wiId);
        ti.setWorkflowNodeId(nodeId);
        ti.setTaskId(taskId);
        ti.setTaskVersionNo(1);
        ti.setRunMode("NORMAL");
        ti.setState(state);
        ti.setAttempt(InstanceStates.SUCCESS.equals(state) ? 1 : (InstanceStates.FAILED.equals(state) ? 1 : 0));
        ti.setWorkerNodeCode(worker);
        ti.setBizDate("2026-06-11");
        ti.setCreatedAt(t);
        ti.setUpdatedAt(t);
        ti.setDeleted(0);
        ti.setVersion(0L);
        taskInstanceRepository.save(ti);
    }

    @Test
    void resume_preservesSuccessNodes_andReRunsFromFailure() throws Exception {
        UUID wiId = buildFailedWorkflowInstance();

        assertThat(recoveryService.resume(wiId)).isTrue();

        boolean done = await(Duration.ofSeconds(20), () ->
                InstanceStates.SUCCESS.equals(
                        workflowInstanceRepository.findById(wiId).map(WorkflowInstance::getState).orElse(null)));
        assertThat(done).as("断点恢复后应续跑至 SUCCESS").isTrue();

        List<TaskInstance> nodes = taskInstanceRepository.findByWorkflowInstanceId(wiId);
        assertThat(nodes).allMatch(n -> InstanceStates.SUCCESS.equals(n.getState()));
    }

    @Test
    void resume_onNonFailedWorkflow_isNoop() {
        LocalDateTime t = LocalDateTime.now();
        WorkflowInstance wi = new WorkflowInstance();
        wi.setTenantId(1L);
        wi.setProjectId(1L);
        wi.setWorkflowId(1L);
        wi.setState(InstanceStates.SUCCESS);
        wi.setPriority(5);
        wi.setCreatedAt(t);
        wi.setUpdatedAt(t);
        wi.setDeleted(0);
        wi.setVersion(0L);
        UUID wiId = workflowInstanceRepository.save(wi).getId();

        assertThat(recoveryService.resume(wiId)).isFalse();
    }

    private boolean await(Duration timeout, java.util.function.BooleanSupplier cond) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return cond.getAsBoolean();
    }
}
