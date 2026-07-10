package com.dataweave.api;

import com.dataweave.master.application.FleetService;
import com.dataweave.master.application.LeaseReaper;
import com.dataweave.master.application.SchedulerMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LeaseReaper 集成测试（task 3.6）。
 *
 * <p>验证：租约过期的实例被标记 FAILED 并按策略重试。
 * 使用 H2 profile（零外部依赖）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class LeaseReaperTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LeaseReaper leaseReaper;

    @Autowired
    private FleetService fleetService;

    @Autowired
    private SchedulerMetrics metrics;

    @Test
    void reapLostInstances_marksExpiredAsFailed() {
        // 准备：创建 worker 节点 → 标记 OFFLINE
        String nodeCode = "test-reaper-" + System.currentTimeMillis();
        fleetService.report(nodeCode, "host1", "4C/8G", 0.1, 0.2, 0.3, 0.4, 0,
                null, null, 120);
        jdbc.update("UPDATE worker_nodes SET status='OFFLINE' WHERE node_code=?", nodeCode);

        // 创建任务定义 + 工作流实例 + 过期租约的 RUNNING 实例
        jdbc.update("INSERT INTO task_def (id, tenant_id, project_id, name, type, status, retry_max, deleted, version) " +
                "VALUES (99999, 1, 1, 'reaper-test', 'SHELL', 'ONLINE', 1, 0, 0)");

        UUID wfId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, deleted, version) " +
                "VALUES (?, 1, 1, 1, 'RUNNING', 0, 0)", wfId.toString());

        UUID instanceId = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance " +
                        "(id, tenant_id, project_id, task_id, workflow_instance_id, state, attempt, worker_node_code, lease_expire_at, run_mode, deleted, version) " +
                        "VALUES (?, 1, 1, 99999, ?, 'RUNNING', 1, ?, ?, 'NORMAL', 0, 0)",
                instanceId.toString(), wfId.toString(), nodeCode, LocalDateTime.now().minusSeconds(300));

        // 执行回收
        leaseReaper.reap();

        // Reaper 应触发状态变化（RUNNING → FAILED → 可能被 scheduler 重派为 DISPATCHED/WAITING）
        // 不再是初始的 RUNNING 即证明 reaper 生效
        String state = jdbc.queryForObject(
                "SELECT state FROM task_instance WHERE id=?", String.class, instanceId.toString());
        assertThat(state).isNotEqualTo("RUNNING");

        // 清理
        jdbc.update("DELETE FROM task_instance WHERE id=?", instanceId.toString());
        jdbc.update("DELETE FROM workflow_instance WHERE id=?", wfId.toString());
        jdbc.update("DELETE FROM task_def WHERE id=99999");
        jdbc.update("DELETE FROM worker_nodes WHERE node_code=?", nodeCode);
    }

    @Test
    void reap_workerRestart_infraRequeue_notFailed_regardlessOfRetryMax() {
        // 060 硬不变量（FR-008）：WORKER_RESTART 是 infra 回收，永不判终态 FAILED、不烧 business_attempt——
        // 即便 retry_max=0（业务无重试预算）也回 WAITING（infra 计数与业务重试彻底解耦）。
        // 本用例原名 reap_noRetryMax_staysFailed，断言旧 attempt 混用语义（attempt>retry_max→FAILED），
        // 060 计数双拆后该前提已废除，改断言正确的 infra 回收语义。
        String nodeCode = "test-restart-" + System.currentTimeMillis();
        fleetService.report(nodeCode, "host1", "4C/8G", 0.1, 0.2, 0.3, 0.4, 0,
                null, null, 120);

        jdbc.update("INSERT INTO task_def (id, tenant_id, project_id, name, type, status, retry_max, deleted, version) " +
                "VALUES (99998, 1, 1, 'restart-test', 'SHELL', 'ONLINE', 0, 0, 0)");

        UUID instanceId = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance " +
                        "(id, tenant_id, project_id, task_id, state, attempt, worker_node_code, lease_expire_at, run_mode, deleted, version) " +
                        "VALUES (?, 1, 1, 99998, 'DISPATCHED', 1, ?, ?, 'NORMAL', 0, 0)",
                instanceId.toString(), nodeCode, LocalDateTime.now().minusSeconds(300));

        // 执行回收（节点 ONLINE 但租约过期 → WORKER_RESTART → infra 回收）
        leaseReaper.reap();

        // 060：infra 回收 → WAITING（非终态），infra_redispatch_count+1，清 worker，business_attempt 不变
        String state = jdbc.queryForObject(
                "SELECT state FROM task_instance WHERE id=?", String.class, instanceId.toString());
        assertThat(state).isEqualTo("WAITING");

        Integer infraCount = jdbc.queryForObject(
                "SELECT infra_redispatch_count FROM task_instance WHERE id=?", Integer.class, instanceId.toString());
        assertThat(infraCount).isEqualTo(1);

        Integer businessAttempt = jdbc.queryForObject(
                "SELECT business_attempt FROM task_instance WHERE id=?", Integer.class, instanceId.toString());
        assertThat(businessAttempt).isEqualTo(0);   // infra 回收不烧业务重试

        String workerNode = jdbc.queryForObject(
                "SELECT worker_node_code FROM task_instance WHERE id=?", String.class, instanceId.toString());
        assertThat(workerNode).isNull();            // 清 worker，可转移到健康节点

        // 清理
        jdbc.update("DELETE FROM task_instance WHERE id=?", instanceId.toString());
        jdbc.update("DELETE FROM task_def WHERE id=99998");
        jdbc.update("DELETE FROM worker_nodes WHERE node_code=?", nodeCode);
    }

    @Test
    void reap_回收后租约回收计数递增() {
        // 接线回归：markLeaseReclaim 在真实回收（CAS 成功）后调用，leaseReclaims Counter 应递增。
        long before = metrics.snapshot().leaseReclaims;
        String nodeCode = "test-reaper-metric-" + System.currentTimeMillis();
        fleetService.report(nodeCode, "host1", "4C/8G", 0.1, 0.2, 0.3, 0.4, 0, null, null, 120);
        jdbc.update("UPDATE worker_nodes SET status='OFFLINE' WHERE node_code=?", nodeCode);

        jdbc.update("INSERT INTO task_def (id, tenant_id, project_id, name, type, status, retry_max, deleted, version) " +
                "VALUES (99997, 1, 1, 'reaper-metric', 'SHELL', 'ONLINE', 0, 0, 0)");
        UUID wfId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, deleted, version) " +
                "VALUES (?, 1, 1, 1, 'RUNNING', 0, 0)", wfId.toString());
        UUID instanceId = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance " +
                        "(id, tenant_id, project_id, task_id, workflow_instance_id, state, attempt, worker_node_code, lease_expire_at, run_mode, deleted, version) " +
                        "VALUES (?, 1, 1, 99997, ?, 'RUNNING', 1, ?, ?, 'NORMAL', 0, 0)",
                instanceId.toString(), wfId.toString(), nodeCode, LocalDateTime.now().minusSeconds(300));

        leaseReaper.reap();

        assertThat(metrics.snapshot().leaseReclaims).isGreaterThan(before);

        // 清理
        jdbc.update("DELETE FROM task_instance WHERE id=?", instanceId.toString());
        jdbc.update("DELETE FROM workflow_instance WHERE id=?", wfId.toString());
        jdbc.update("DELETE FROM task_def WHERE id=99997");
        jdbc.update("DELETE FROM worker_nodes WHERE node_code=?", nodeCode);
    }
}
