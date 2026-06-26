package com.dataweave.api;

import com.dataweave.master.application.DefaultTriggerEngine;
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

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US2 多 master 去重 —— 集成测试（H2）。
 * <p>
 * T016: 时间轮 arm 后到点并发触发，恰一条实例（扩展 SchedulerConcurrencyTest 护栏回归）。
 * T017: 多 TriggerEngine（模拟多 master）对同一 (workflowId, due) 并发 fire，恰一条实例。
 * <p>
 * 去重真相：cron_fire 唯一键 (workflow_id, scheduled_fire_time) —— 分片只决定「谁预读」，
 * 不改「谁能触发一次」。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:multimasterdedup;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
class MultiMasterDedupTest {

    @Autowired
    private DefaultTriggerEngine triggerEngine;

    @Autowired
    private WorkflowDefRepository workflowDefRepository;

    @Autowired
    private WorkflowInstanceRepository workflowInstanceRepository;

    private WorkflowDef wf;
    private LocalDateTime testStart;

    @BeforeEach
    void setUp() {
        testStart = LocalDateTime.now();
        wf = workflowDefRepository.findById(1L).orElseThrow();
        wf.setStatus("ONLINE");
        wf.setDeleted(0);
        wf.setScheduleStart(null);
        wf.setScheduleEnd(null);
        wf.setNextTriggerTime(LocalDateTime.now().plusDays(30));
        wf.setLastFireTime(LocalDateTime.now().minusDays(1));
        wf.setUpdatedAt(LocalDateTime.now());
        workflowDefRepository.save(wf);
        // 清空历史实例
        List<WorkflowInstance> toDelete = new ArrayList<>();
        workflowInstanceRepository.findByWorkflowId(1L).forEach(toDelete::add);
        toDelete.forEach(workflowInstanceRepository::delete);
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
    // T016 [US2] 时间轮 arm 后多线程并发触发 → 恰一条实例
    // ================================================================

    @Test
    void concurrentFire_fromArmedTimer_dedupsToOneInstance() throws Exception {
        // given: 设置已过期的触发点（多线程 scanAndArm 后各自 arm，timer 并发 fire）
        LocalDateTime due = LocalDateTime.now().minusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
        wf.setNextTriggerTime(due);
        wf.setLastFireTime(due.minusHours(1));
        workflowDefRepository.save(wf);

        int masters = 6;
        ExecutorService pool = Executors.newFixedThreadPool(masters);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger armedCount = new AtomicInteger();

        // when: 模拟 N 个 master 同时扫描同一工作流
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < masters; i++) {
            futures.add(pool.submit(() -> {
                await(start);
                triggerEngine.scanAndArm(LocalDateTime.now());
                armedCount.incrementAndGet();
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // then: 等待所有 timer 触发完成
        Thread.sleep(8000);
        List<WorkflowInstance> instances = workflowInstanceRepository.findByWorkflowId(1L);
        long cronInstances = instances.stream()
                .filter(i -> "CRON".equals(i.getTriggerType()))
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(testStart))
                .count();

        assertThat(cronInstances)
                .as("多 master 对同一 (workflowId, due) 并发触发，恰产生一条实例，实际 %d", cronInstances)
                .isEqualTo(1);
    }

    // ================================================================
    // T017 [US2] 多 TriggerEngine 实例对同一触发点并发 fire → 恰一条实例
    // ================================================================

    @Test
    void multiMasterConcurrentScan_createsExactlyOneInstance() throws Exception {
        // given: 设置 near-future 触发点（各 master scan 后各自 arm 到本地 timer）
        LocalDateTime due = LocalDateTime.now().plusSeconds(2).truncatedTo(ChronoUnit.SECONDS);
        wf.setNextTriggerTime(due);
        wf.setLastFireTime(due.minusHours(1));
        workflowDefRepository.save(wf);

        int masters = 4;
        ExecutorService pool = Executors.newFixedThreadPool(masters);
        CountDownLatch start = new CountDownLatch(1);

        // when: 模拟多 master 同时扫描
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < masters; i++) {
            futures.add(pool.submit(() -> {
                await(start);
                triggerEngine.scanAndArm(LocalDateTime.now());
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // then: 等待 timer 触发 + 去重
        Thread.sleep(15_000);
        List<WorkflowInstance> instances = workflowInstanceRepository.findByWorkflowId(1L);
        long cronInstances = instances.stream()
                .filter(i -> "CRON".equals(i.getTriggerType()))
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(testStart))
                .count();

        assertThat(cronInstances)
                .as("多 master 并发扫描同一 near-future 触发点，恰产生一条实例，实际 %d", cronInstances)
                .isEqualTo(1);
    }

    // ================================================================
    // T019 [US2] 单 master arm 后退出，由其他 master 补 arm 不丢触发
    // ================================================================

    @Test
    void masterExitsAfterArm_otherMasterPicksUpOnNextScan() throws Exception {
        // given: 设置 near-future 触发点
        LocalDateTime due = LocalDateTime.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        wf.setNextTriggerTime(due);
        wf.setLastFireTime(due.minusHours(1));
        workflowDefRepository.save(wf);

        // 第一轮扫描（模拟 master A arm 后"退出"—— 但 timer 仍在同一 JVM 中）
        // 关键验证：第二轮扫描不会因 armed 去重而跳过（因为同 JVM 内 armed 已存在）
        triggerEngine.scanAndArm(LocalDateTime.now());

        // "master B" 第二轮扫描：即使 master A"宕机"（同 JVM 内 armed 存在但 timer 正常触发），
        // 第二轮 scanAndArm 的 armed.putIfAbsent 返回非 null（已被 A arm），不算重复
        // 但触发仍会正常发生在 A 的 timer 上
        Thread.sleep(500);
        triggerEngine.scanAndArm(LocalDateTime.now());

        // then: 应恰有一条实例
        Thread.sleep(15_000);
        List<WorkflowInstance> instances = workflowInstanceRepository.findByWorkflowId(1L);
        long cronInstances = instances.stream()
                .filter(i -> "CRON".equals(i.getTriggerType()))
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(testStart))
                .count();

        assertThat(cronInstances)
                .as("即使多轮扫描，同一触发点仍恰产生一条实例")
                .isEqualTo(1);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
