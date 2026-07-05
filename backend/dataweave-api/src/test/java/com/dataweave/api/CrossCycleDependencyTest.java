package com.dataweave.api;

import com.dataweave.master.application.SchedulerKernel;
import com.dataweave.master.application.WorkflowTriggerService;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨周期依赖就绪端到端（3.5 / 7.4）：独立 H2 库 crosscycle 隔离（trigger 全节点造历史，跨测试需隔离 dep）。
 *
 * <p>覆盖 SchedulerKernel.crossCycleReady（仅 CRON 实例检查，手动/TEST 天然豁免）：
 * <ul>
 *   <li>自依赖：上一周期 SUCCESS → 本周期就绪聚合 SUCCESS；</li>
 *   <li>earliest 首周期豁免：bizDate &lt; earliest_biz_date 时不等上一周期直跑；</li>
 *   <li>无上一周期：自依赖节点被阻塞在 WAITING。</li>
 * </ul>
 * wf3「订单 SHELL 流水线」6 节点 CRON ONLINE，用末端 n6(node9) 配自依赖。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:crosscycle;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
// ec7868e 移除 worker_nodes 产品 seed 后,MOCK 环境无 HTTP 心跳注册,调度 E2E 须自备 ONLINE worker。
@Sql(scripts = "/test-worker-seed.sql")
class CrossCycleDependencyTest {

    @Autowired WorkflowTriggerService triggerService;
    @Autowired WorkflowDefRepository workflowDefRepository;
    @Autowired WorkflowInstanceRepository workflowInstanceRepository;
    @Autowired SchedulerKernel schedulerKernel;
    @Autowired JdbcTemplate jdbc;

    private static final Locale LOC = Locale.SIMPLIFIED_CHINESE;

    @AfterEach
    void cleanDeps() {
        // 每个用例自插的跨周期依赖软/硬清掉，避免跨用例污染（trigger 造的 instance 按 bizDate 天然隔离，不清）
        jdbc.update("DELETE FROM workflow_dependency WHERE workflow_id=3");
    }

    @Test
    void selfDependence_prevCycleSuccess_releasesCurrent() throws Exception {
        WorkflowDef wf = workflowDefRepository.findById(3L).orElseThrow();
        // step1：上一周期 CRON 2026-06-09（尚无 dep）→ 全 SUCCESS，造 n6 历史成功实例
        UUID prev = triggerService.trigger(wf, "CRON", "2026-06-09", null, LOC);
        assertThat(await(Duration.ofSeconds(20), () -> InstanceStates.SUCCESS.equals(state(prev))))
                .as("前置：上一周期应聚合 SUCCESS").isTrue();

        // step2：配 n6(node9) 自依赖 earliest=2026-06-09 LAST_DAY（上一周期）
        insertSelfDep(9L, "2026-06-09");

        // step3：本周期 CRON 2026-06-10 → n6 自依赖查 2026-06-09(LAST_DAY -1) SUCCESS → 就绪 → 聚合 SUCCESS
        UUID cur = triggerService.trigger(wf, "CRON", "2026-06-10", null, LOC);
        boolean ok = await(Duration.ofSeconds(20), () -> InstanceStates.SUCCESS.equals(state(cur)));
        assertThat(ok).as("自依赖：上一周期 SUCCESS → 本周期就绪聚合 SUCCESS").isTrue();
    }

    @Test
    void selfDependence_firstCycleExempt_runsDirectly() throws Exception {
        WorkflowDef wf = workflowDefRepository.findById(3L).orElseThrow();
        // earliest=2026-06-14：本周期 bizDate=2026-06-13 < earliest → 首周期豁免（不等上一周期）直跑
        insertSelfDep(9L, "2026-06-14");
        UUID cur = triggerService.trigger(wf, "CRON", "2026-06-13", null, LOC);
        boolean ok = await(Duration.ofSeconds(20), () -> InstanceStates.SUCCESS.equals(state(cur)));
        assertThat(ok).as("earliest 首周期豁免：bizDate<earliest 不等上一周期直跑 SUCCESS").isTrue();
    }

    @Test
    void selfDependence_noPrevCycle_blocksAtWaiting() throws Exception {
        WorkflowDef wf = workflowDefRepository.findById(3L).orElseThrow();
        // earliest=2026-06-20：本周期 bizDate=2026-06-20，查上一周期 2026-06-19（无任何历史）→ n6 阻塞
        insertSelfDep(9L, "2026-06-20");
        UUID cur = triggerService.trigger(wf, "CRON", "2026-06-20", null, LOC);
        // 等前驱链跑到 n5，n6 就绪门（边）通过但 crossCycleReady 自依赖阻塞
        await(Duration.ofSeconds(15), () -> InstanceStates.SUCCESS.equals(
                taskInstanceState(instanceIdForNode(cur, 8L)))); // n5(node8) 成功表示前驱链已就绪
        schedulerKernel.scheduleOnce();
        Thread.sleep(1500); // 让本轮认领事务落实
        String n6State = taskInstanceState(instanceIdForNode(cur, 9L));
        assertThat(n6State).as("无上一周期：自依赖节点应被阻塞在 WAITING").isEqualTo(InstanceStates.WAITING);
    }

    // ---- helpers ----

    private void insertSelfDep(Long nodeId, String earliest) {
        jdbc.update("INSERT INTO workflow_dependency(tenant_id,project_id,workflow_id,node_id,depend_workflow_id,"
                + "depend_node_id,date_offset,dep_type,earliest_biz_date,enabled,created_at,updated_at,deleted,version) "
                + "VALUES(1,1,3,?,3,?,'LAST_DAY','CYCLE',?,1,NOW(),NOW(),0,0)", nodeId, nodeId, earliest);
    }

    private String state(UUID wiId) {
        return workflowInstanceRepository.findById(wiId).map(w -> w.getState()).orElse(null);
    }

    private UUID instanceIdForNode(UUID wiId, Long nodeId) {
        return jdbc.queryForObject(
                "SELECT id FROM task_instance WHERE workflow_instance_id=? AND workflow_node_id=? AND deleted=0",
                UUID.class, wiId, nodeId);
    }

    private String taskInstanceState(UUID id) {
        return jdbc.queryForObject("SELECT state FROM task_instance WHERE id=?", String.class, id);
    }

    private boolean await(Duration timeout, java.util.function.BooleanSupplier cond) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return cond.getAsBoolean();
    }
}
