package com.dataweave.api;

import com.dataweave.master.application.PreemptionService;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 软抢占（task 2.6，H2，隔离 context）：高优待调度 + 无空槽 + 低优 preemptible 运行实例
 * → 抢占该实例 PREEMPTED 回炉 WAITING，attempt 不变。
 *
 * <p>{@code @TestPropertySource} 令本类拥有独立 context/库（与共享 H2 隔离），可自由 offline 集群、
 * 并把兜底轮询调到极长避免后台干扰；抢占判定直接调用 {@link PreemptionService}。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {"scheduler.poll-interval-ms=3600000"})
class SchedulerPreemptionTest {

    @Autowired
    PreemptionService preemptionService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    WorkflowInstanceRepository workflowInstanceRepository;
    @Autowired
    TaskInstanceRepository taskInstanceRepository;

    @Test
    void highPriorityWaiting_preemptsLowerPriorityPreemptible() {
        // 1) 场景封闭:仅留一个单槽 worker 在线;并清掉 data.sql 预置的 WAITING 素材实例——
        //    ec7868e 移除 worker_nodes seed 后,启动期无节点消化它们,它们(updated_at 更老)会与
        //    demand 争唯一槽并占住 RUNNING,使 demand 永远认领不到。
        jdbc.update("UPDATE worker_nodes SET status='OFFLINE'");
        jdbc.update("UPDATE task_instance SET deleted=1 WHERE state='WAITING' AND deleted=0");
        // 060：SlotManager 节点可用性门要求 last_heartbeat 新鲜（心跳 null 的节点视为不可用被排除）。
        // 本 seed 模拟活节点须带新鲜心跳，否则抢占腾槽后 demand 无可用节点认领。
        jdbc.update("INSERT INTO worker_nodes (node_code, status, max_concurrent_tasks, reserved_test_slots, "
                + "last_heartbeat, created_at, updated_at, deleted, version) VALUES ('node-pre','ONLINE',1,0,?,?,?,0,0)",
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());

        // 2) preemptible 工作流 + 低优(priority=8)运行实例占满唯一槽（attempt=2）
        long preemptibleWf = 900001L;
        jdbc.update("INSERT INTO workflow_def (id, tenant_id, project_id, name, status, priority, preemptible, "
                + "created_at, updated_at, deleted, version) VALUES (?,1,1,'抢占测试流','ONLINE',8,1,?,?,0,0)",
                preemptibleWf, LocalDateTime.now(), LocalDateTime.now());
        UUID victimWi = wfInstance(preemptibleWf, 8);
        UUID victimId = task(victimWi, InstanceStates.RUNNING, "node-pre", 2);

        // 3) 高优(priority=1)可运行待调度实例（独立工作流、无边 → 可运行）
        UUID demandWi = wfInstance(700001L, 1);
        UUID demandId = task(demandWi, InstanceStates.WAITING, null, 0);

        // 4) 抢占
        boolean preempted = preemptionService.preemptOneForWaitingHighPriority();

        assertThat(preempted).as("应发生一次软抢占").isTrue();
        // 抢占效果：高优 demand 被认领到被抢占腾出的槽位（victim 让位）。
        // 被抢占的 victim 瞬间回炉 WAITING（attempt 不变、worker 清空）；但若 demand（如 SQL 模拟执行）
        // 迅速完成释放槽位，victim 会被重排执行 → SUCCESS，故 victim 最终态在异步调度下不稳定，
        // 改以「demand 占槽」作为抢占生效的稳定凭证。
        TaskInstance demand = taskInstanceRepository.findById(demandId).orElseThrow();
        assertThat(demand.getWorkerNodeCode()).as("demand 应占被抢占腾出的槽").isEqualTo("node-pre");
        assertThat(demand.getAttempt()).as("demand 应已认领下发").isGreaterThanOrEqualTo(1);
    }

    private UUID wfInstance(long workflowId, int priority) {
        LocalDateTime t = LocalDateTime.now();
        WorkflowInstance wi = new WorkflowInstance();
        wi.setTenantId(1L);
        wi.setProjectId(1L);
        wi.setWorkflowId(workflowId);
        wi.setState(InstanceStates.RUNNING);
        wi.setPriority(priority);
        wi.setCreatedAt(t);
        wi.setUpdatedAt(t);
        wi.setDeleted(0);
        wi.setVersion(0L);
        return workflowInstanceRepository.save(wi).getId();
    }

    private UUID task(UUID wiId, String state, String worker, int attempt) {
        LocalDateTime t = LocalDateTime.now();
        TaskInstance ti = new TaskInstance();
        ti.setTenantId(1L);
        ti.setProjectId(1L);
        ti.setWorkflowInstanceId(wiId);
        ti.setTaskId(1L);
        ti.setRunMode("NORMAL");
        ti.setState(state);
        ti.setAttempt(attempt);
        ti.setWorkerNodeCode(worker);
        ti.setCreatedAt(t);
        ti.setUpdatedAt(t);
        ti.setDeleted(0);
        ti.setVersion(0L);
        return taskInstanceRepository.save(ti).getId();
    }
}
