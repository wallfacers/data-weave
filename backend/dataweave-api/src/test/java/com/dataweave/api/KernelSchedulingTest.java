package com.dataweave.api;

import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import com.dataweave.master.application.WorkflowTriggerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调度内核端到端（all-in-one，H2）：触发工作流 → 认领 → 下发 → 进程内执行 → 回报 → DAG 下游解锁
 * → 工作流聚合 SUCCESS（task 2.2/2.3/2.4/2.5 + 执行闭环）。
 */
@SpringBootTest
@ActiveProfiles("h2")
class KernelSchedulingTest {

    @Autowired
    WorkflowTriggerService triggerService;
    @Autowired
    WorkflowDefRepository workflowDefRepository;
    @Autowired
    WorkflowInstanceRepository workflowInstanceRepository;
    @Autowired
    TaskInstanceRepository taskInstanceRepository;

    @Test
    void triggeredWorkflow_runsAllNodes_andAggregatesSuccess() throws Exception {
        WorkflowDef wf = workflowDefRepository.findById(1L).orElseThrow();

        UUID wiId = triggerService.trigger(wf, "MANUAL", "2026-06-11", null);

        boolean done = await(Duration.ofSeconds(20), () ->
                InstanceStates.SUCCESS.equals(
                        workflowInstanceRepository.findById(wiId).map(w -> w.getState()).orElse(null)));

        assertThat(done).as("工作流应在超时内聚合为 SUCCESS").isTrue();

        List<TaskInstance> nodes = taskInstanceRepository.findByWorkflowInstanceId(wiId);
        assertThat(nodes).isNotEmpty();
        assertThat(nodes).allMatch(n -> InstanceStates.SUCCESS.equals(n.getState()));
        // 每个节点都被真实下发执行（有 worker 落点 + attempt 递增）
        assertThat(nodes).allMatch(n -> n.getWorkerNodeCode() != null);
        assertThat(nodes).allMatch(n -> n.getAttempt() != null && n.getAttempt() >= 1);
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
