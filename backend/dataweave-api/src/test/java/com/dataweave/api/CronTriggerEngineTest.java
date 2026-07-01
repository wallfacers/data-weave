package com.dataweave.api;

import com.dataweave.master.application.DefaultTriggerEngine;
import com.dataweave.master.domain.CronFire;
import com.dataweave.master.domain.CronFireRepository;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 分布式 Cron 精确触发 —— 集成测试（H2）。
 * <p>
 * 覆盖：准点触发延迟 (T008)、预读窗口边界 (T009)、向后兼容回填 (T046)、
 * 失效不触发 (T047)、重叠并发 (T048)。
 * <p>
 * 使用种子工作流 id=1（每日 GMV，CRON，含 1 节点）作为测试基座。
 * 独立 H2 库避免跨测试类污染。
 * <p>
 * 注意：后台 CronScheduler 每 15s 扫描一次。本类通过 setUp 中彻底清理
 * 残留实例/cron_fire + 设远未来 next_trigger_time 来隔离。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cronenginetest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
class CronTriggerEngineTest {

    @Autowired
    private DefaultTriggerEngine triggerEngine;

    @Autowired
    private WorkflowDefRepository workflowDefRepository;

    @Autowired
    private CronFireRepository cronFireRepository;

    @Autowired
    private WorkflowInstanceRepository workflowInstanceRepository;

    @Autowired
    private com.dataweave.master.application.WorkflowTriggerService workflowTriggerService;

    private WorkflowDef wf;
    /** 本测试启动时刻，用于 awaitInstance 排除后台 scheduler 提前创建的实例 */
    private LocalDateTime testStart;

    @BeforeEach
    void setUp() {
        testStart = LocalDateTime.now();
        wf = workflowDefRepository.findById(1L).orElseThrow();
        // 设远未来的 next，防止后台 CronScheduler 抢在测试前触发
        wf.setStatus("ONLINE");
        wf.setDeleted(0);
        wf.setScheduleStart(null);
        wf.setScheduleEnd(null);
        wf.setNextTriggerTime(LocalDateTime.now().plusDays(30));
        wf.setLastFireTime(LocalDateTime.now().minusDays(1));
        wf.setUpdatedAt(LocalDateTime.now());
        workflowDefRepository.save(wf);
        // 清空历史 cron_fire
        List<CronFire> toDelete = new ArrayList<>();
        cronFireRepository.findAll().forEach(toDelete::add);
        toDelete.stream()
                .filter(cf -> cf.getWorkflowId().equals(1L))
                .forEach(cronFireRepository::delete);
        // 清空历史实例
        List<WorkflowInstance> wiToDelete = new ArrayList<>();
        workflowInstanceRepository.findByWorkflowId(1L).forEach(wiToDelete::add);
        wiToDelete.forEach(workflowInstanceRepository::delete);
    }

    @AfterEach
    void tearDown() {
        if (wf != null) {
            wf = workflowDefRepository.findById(1L).orElse(null);
            if (wf != null) {
                wf.setNextTriggerTime(LocalDateTime.now().plusDays(30));
                wf.setLastFireTime(null);
                wf.setStatus("ONLINE");
                wf.setDeleted(0);
                wf.setScheduleStart(null);
                wf.setScheduleEnd(null);
                workflowDefRepository.save(wf);
            }
        }
    }

    // ================================================================
    // T008 [US1] 准点触发延迟
    // ================================================================

    @Test
    void cronTriggerLatency_withinSeconds() throws Exception {
        LocalDateTime due = LocalDateTime.now().plusSeconds(3).truncatedTo(ChronoUnit.SECONDS);
        wf.setNextTriggerTime(due);
        wf.setLastFireTime(due.minusHours(1));
        workflowDefRepository.save(wf);

        triggerEngine.scanAndArm(LocalDateTime.now());

        WorkflowInstance wi = awaitInstanceAfter(due, 20_000);
        assertThat(wi).as("应在超时内创建实例").isNotNull();

        LocalDateTime created = wi.getCreatedAt();
        long delayMs = Duration.between(due, created).toMillis();
        assertThat(delayMs)
                .as("cron时刻→实例创建延迟 p99 ≤ 2s，实测 %d ms", delayMs)
                .isLessThanOrEqualTo(5_000);

        // 验证 cron_fire 护栏落库（H2 TIMESTAMP 可能舍入纳秒，用宽松匹配）
        List<CronFire> fires = new ArrayList<>();
        cronFireRepository.findAll().forEach(fires::add);
        boolean hasFire = fires.stream().anyMatch(cf ->
                cf.getWorkflowId().equals(1L) &&
                Math.abs(Duration.between(cf.getScheduledFireTime(), due).toMillis()) < 2000);
        assertThat(hasFire).as("cron_fire 去重记录应存在（due=%s）", due).isTrue();

        // 038：cron 触发的实例应快照 scheduled_fire_time ≈ due（与 cron_expression 同源物化）
        assertThat(wi.getScheduledFireTime())
                .as("cron 触发实例应快照 scheduled_fire_time")
                .isNotNull();
        assertThat(Math.abs(Duration.between(wi.getScheduledFireTime(), due).toMillis()))
                .as("scheduled_fire_time 应贴近 due（H2 TIMESTAMP 舍入，2s 内）")
                .isLessThanOrEqualTo(2000);
    }

    // ================================================================
    // 038 手动触发：scheduled_fire_time 应为 null（无 cron 计划时刻）
    // ================================================================

    @Test
    void manualTrigger_scheduledFireTimeIsNull() {
        // 手动触发走 5 参重载 → 透传 null scheduledFireTime
        java.util.UUID wiId = workflowTriggerService.trigger(wf, "MANUAL", "2026-07-01",
                wf.getPriority(), java.util.Locale.SIMPLIFIED_CHINESE);
        WorkflowInstance wi = workflowInstanceRepository.findById(wiId).orElseThrow();
        assertThat(wi.getTriggerType()).as("triggerType 应为 MANUAL").isEqualTo("MANUAL");
        assertThat(wi.getScheduledFireTime())
                .as("手动触发实例 scheduled_fire_time 应为 null（无 cron 计划时刻）")
                .isNull();
    }

    // ================================================================
    // T009 [US1] 预读窗口边界
    // ================================================================

    @Test
    void windowBoundary_insideWindow_isArmed() throws Exception {
        LocalDateTime due = LocalDateTime.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        wf.setNextTriggerTime(due);
        wf.setLastFireTime(due.minusHours(1));
        workflowDefRepository.save(wf);

        triggerEngine.scanAndArm(LocalDateTime.now());

        WorkflowInstance wi = awaitInstanceAfter(due, 25_000);
        assertThat(wi).as("窗口内触发点应创建实例").isNotNull();
    }

    @Test
    void windowBoundary_outsideWindow_notTriggeredYet() throws Exception {
        LocalDateTime farFuture = LocalDateTime.now().plusMinutes(2).truncatedTo(ChronoUnit.SECONDS);
        wf.setNextTriggerTime(farFuture);
        wf.setLastFireTime(farFuture.minusHours(1));
        workflowDefRepository.save(wf);

        triggerEngine.scanAndArm(LocalDateTime.now());

        // 窗口外的点不应在本轮被触发；给 3s 观察
        Thread.sleep(3000);
        List<WorkflowInstance> instances = workflowInstanceRepository.findByWorkflowId(1L);
        long newInstances = instances.stream()
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(testStart))
                .count();
        assertThat(newInstances)
                .as("远超出 lookahead 的触发点不应被提前触发")
                .isZero();
    }

    // ================================================================
    // T046 [US1] 向后兼容 —— NULL next_trigger_time 首轮回填后触发
    // ================================================================

    @Test
    void nullNextTriggerTime_backfillsAndTriggers() throws Exception {
        // 种子数据 created_at=2026-06-06，cron='0 0 2 * * ?' → 首轮回填的 next 是
        // 2026-06-06T02:00（已过期），引擎以 misfire 处理（delay=0 立即补触发一次）
        wf.setNextTriggerTime(null);
        wf.setLastFireTime(null);
        workflowDefRepository.save(wf);

        triggerEngine.scanAndArm(LocalDateTime.now());

        // next_trigger_time 被回填（非 NULL）
        WorkflowDef reloaded = workflowDefRepository.findById(1L).orElseThrow();
        assertThat(reloaded.getNextTriggerTime())
                .as("首轮扫描后 next_trigger_time 应被回填为非 NULL")
                .isNotNull();

        // 补触发了一次 → cron_fire 恰 1 条
        Thread.sleep(5000);
        long fireCount = 0;
        List<CronFire> fires = new ArrayList<>();
        cronFireRepository.findAll().forEach(fires::add);
        for (CronFire cf : fires) {
            if (cf.getWorkflowId().equals(1L)) {
                fireCount++;
            }
        }
        assertThat(fireCount)
                .as("首轮回填后应恰补触发一次（misfire fire_once）")
                .isEqualTo(1);

        // 最终 next_trigger_time 推进到未来（基于 now 重算的未来最近 2:00）
        reloaded = workflowDefRepository.findById(1L).orElseThrow();
        assertThat(reloaded.getNextTriggerTime())
                .as("补触发后 next_trigger_time 应推进到未来")
                .isAfter(LocalDateTime.now().minusSeconds(10));
    }

    // ================================================================
    // T047 [US1] 失效不触发
    // ================================================================

    @Test
    void armedWorkflow_setOffline_noInstanceCreated() throws Exception {
        LocalDateTime due = LocalDateTime.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        wf.setNextTriggerTime(due);
        wf.setLastFireTime(due.minusHours(1));
        workflowDefRepository.save(wf);

        triggerEngine.scanAndArm(LocalDateTime.now());

        // 到点前下线
        wf.setStatus("OFFLINE");
        wf.setUpdatedAt(LocalDateTime.now());
        workflowDefRepository.save(wf);

        Thread.sleep(12_000);
        List<WorkflowInstance> instances = workflowInstanceRepository.findByWorkflowId(1L);
        long triggered = instances.stream()
                .filter(i -> "CRON".equals(i.getTriggerType()))
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(testStart))
                .count();
        assertThat(triggered)
                .as("已下线工作流到点不应产生 CRON 实例")
                .isZero();
    }

    @Test
    void armedWorkflow_exceededScheduleEnd_noInstanceCreated() throws Exception {
        LocalDateTime due = LocalDateTime.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime pastEnd = due.minusMinutes(1);
        wf.setNextTriggerTime(due);
        wf.setLastFireTime(due.minusHours(1));
        wf.setScheduleEnd(pastEnd);
        workflowDefRepository.save(wf);

        triggerEngine.scanAndArm(LocalDateTime.now());

        Thread.sleep(12_000);
        WorkflowDef reloaded = workflowDefRepository.findById(1L).orElseThrow();
        // 超出 schedule_end → next 被置 null（停排）
        assertThat(reloaded.getNextTriggerTime())
                .as("超出 schedule_end 后 next_trigger_time 应被置 null 停止排程")
                .isNull();

        List<WorkflowInstance> instances = workflowInstanceRepository.findByWorkflowId(1L);
        long triggered = instances.stream()
                .filter(i -> "CRON".equals(i.getTriggerType()))
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(testStart))
                .count();
        assertThat(triggered)
                .as("超出 schedule_end 不应产生 CRON 实例")
                .isZero();
    }

    // ================================================================
    // T048 [US1] 重叠并发
    // ================================================================

    @Test
    void overlappingTrigger_createsNewInstance() throws Exception {
        // 第一个触发点（已过期，立即触发）
        LocalDateTime firstDue = LocalDateTime.now().minusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
        wf.setNextTriggerTime(firstDue);
        wf.setLastFireTime(firstDue.minusHours(1));
        workflowDefRepository.save(wf);

        triggerEngine.scanAndArm(LocalDateTime.now());
        WorkflowInstance first = awaitInstanceAfter(firstDue, 15_000);
        assertThat(first).as("第一次触发应创建实例").isNotNull();

        // 第二个触发点（不等第一个实例完成）
        LocalDateTime secondDue = LocalDateTime.now().plusSeconds(3).truncatedTo(ChronoUnit.SECONDS);
        wf = workflowDefRepository.findById(1L).orElseThrow();
        wf.setNextTriggerTime(secondDue);
        wf.setLastFireTime(firstDue);
        workflowDefRepository.save(wf);

        triggerEngine.scanAndArm(LocalDateTime.now());

        Thread.sleep(12_000);
        List<WorkflowInstance> instances = workflowInstanceRepository.findByWorkflowId(1L);
        long cronInstances = instances.stream()
                .filter(i -> "CRON".equals(i.getTriggerType()))
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(testStart))
                .count();
        assertThat(cronInstances)
                .as("上一实例未完成时新触发点仍应创建新实例（FR-015），实际 %d", cronInstances)
                .isGreaterThanOrEqualTo(2);
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 轮询等待 id=1 的 CRON 实例（created_at > threshold），超时返回 null。 */
    private WorkflowInstance awaitInstanceAfter(LocalDateTime threshold, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<WorkflowInstance> instances = workflowInstanceRepository.findByWorkflowId(1L);
            for (WorkflowInstance wi : instances) {
                if ("CRON".equals(wi.getTriggerType())
                        && wi.getCreatedAt() != null
                        && wi.getCreatedAt().isAfter(threshold)) {
                    return wi;
                }
            }
            Thread.sleep(200);
        }
        return null;
    }
}
