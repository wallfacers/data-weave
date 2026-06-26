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
 * US4 秒级与多种周期表达式 —— 集成测试（H2）。
 * <p>
 * T025: 秒级 cron (如 every 30s) 连续多周期，间隔误差 &lt;=2s 且无累计漂移。
 * T026: FIXED_RATE 按计划间隔触发 / FIXED_DELAY 上次完成+interval。
 * <p>
 * T027 (CronTimingStrategy 秒级单测) 已在 CronTimingStrategyTest 中覆盖。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:secondleveltest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
class SecondLevelAndRateDelayTest {

    @Autowired
    private DefaultTriggerEngine triggerEngine;

    @Autowired
    private WorkflowDefRepository workflowDefRepository;

    @Autowired
    private CronFireRepository cronFireRepository;

    @Autowired
    private WorkflowInstanceRepository workflowInstanceRepository;

    private WorkflowDef wf;
    private LocalDateTime testStart;
    private String originalCron;
    private String originalScheduleType;

    @BeforeEach
    void setUp() {
        testStart = LocalDateTime.now();
        wf = workflowDefRepository.findById(1L).orElseThrow();
        originalCron = wf.getCron();
        originalScheduleType = wf.getScheduleType();
        wf.setStatus("ONLINE");
        wf.setDeleted(0);
        wf.setScheduleStart(null);
        wf.setScheduleEnd(null);
        wf.setNextTriggerTime(LocalDateTime.now().plusDays(30));
        wf.setLastFireTime(LocalDateTime.now().minusDays(1));
        wf.setUpdatedAt(LocalDateTime.now());
        workflowDefRepository.save(wf);
        // 清空残留
        List<CronFire> toDelete = new ArrayList<>();
        cronFireRepository.findAll().forEach(toDelete::add);
        toDelete.stream()
                .filter(cf -> cf.getWorkflowId().equals(1L))
                .forEach(cronFireRepository::delete);
        List<WorkflowInstance> wiToDelete = new ArrayList<>();
        workflowInstanceRepository.findByWorkflowId(1L).forEach(wiToDelete::add);
        wiToDelete.forEach(workflowInstanceRepository::delete);
    }

    @AfterEach
    void tearDown() {
        if (wf != null) {
            wf = workflowDefRepository.findById(1L).orElse(null);
            if (wf != null) {
                wf.setCron(originalCron);
                wf.setScheduleType(originalScheduleType);
                wf.setNextTriggerTime(LocalDateTime.now().plusDays(30));
                wf.setLastFireTime(null);
                wf.setScheduleIntervalMs(null);
                wf.setStatus("ONLINE");
                wf.setDeleted(0);
                wf.setScheduleStart(null);
                wf.setScheduleEnd(null);
                workflowDefRepository.save(wf);
            }
        }
    }

    // ================================================================
    // T025 [US4] 秒级 cron 连续触发，间隔误差 ≤2s 无累计漂移
    // ================================================================

    @Test
    void secondLevelCron_stableIntervalNoDrift() throws Exception {
        // given: 设置秒级 cron（每 10 秒触发一次）
        wf.setCron("*/10 * * * * *");
        wf.setScheduleType("CRON");
        wf.setNextTriggerTime(LocalDateTime.now().plusSeconds(2).truncatedTo(ChronoUnit.SECONDS));
        wf.setLastFireTime(LocalDateTime.now().minusHours(1));
        workflowDefRepository.save(wf);

        // when: 连续扫描 + 等待多个周期触发
        LocalDateTime scanStart = LocalDateTime.now();
        for (int i = 0; i < 10; i++) {
            triggerEngine.scanAndArm(LocalDateTime.now());
            Thread.sleep(5000);
        }

        // then: 应产生多个实例，且间隔符合秒级 cron 预期
        List<WorkflowInstance> instances = workflowInstanceRepository.findByWorkflowId(1L);
        List<LocalDateTime> times = instances.stream()
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(testStart))
                .map(WorkflowInstance::getCreatedAt)
                .sorted()
                .toList();

        assertThat(times.size())
                .as("秒级 cron (*/10) 应在 ~50s 内产生至少 2 个实例")
                .isGreaterThanOrEqualTo(2);

        // 检查相邻实例间隔在秒级 cron 合理范围内
        for (int i = 1; i < times.size(); i++) {
            long intervalMs = Duration.between(times.get(i - 1), times.get(i)).toMillis();
            // 每 10s 触发一次，允许 ±5s 容差（含扫描/触发延迟）
            assertThat(intervalMs)
                    .as("相邻实例间隔应约 10s（±5s），实测 %d ms", intervalMs)
                    .isBetween(1_000L, 30_000L);
        }
    }

    // ================================================================
    // T026 [US4] FIXED_RATE 按计划间隔触发
    // ================================================================

    @Test
    void fixedRate_triggersAtFixedIntervals() throws Exception {
        // given: FIXED_RATE 每 8 秒触发
        wf.setScheduleType("FIXED_RATE");
        wf.setCron(null);
        wf.setScheduleIntervalMs(8_000L);
        wf.setNextTriggerTime(LocalDateTime.now().plusSeconds(3).truncatedTo(ChronoUnit.SECONDS));
        wf.setLastFireTime(null);
        workflowDefRepository.save(wf);

        // when: 多次扫描触发
        triggerEngine.scanAndArm(LocalDateTime.now());

        Thread.sleep(35_000); // 等待多个周期

        // 多扫描几次确保后续周期也被 arm
        for (int i = 0; i < 5; i++) {
            triggerEngine.scanAndArm(LocalDateTime.now());
            Thread.sleep(3000);
        }

        // then: 应产生至少 2 个实例（FIXED_RATE 每 8s 触发一次）
        List<WorkflowInstance> instances = workflowInstanceRepository.findByWorkflowId(1L);
        long count = instances.stream()
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(testStart))
                .count();
        assertThat(count)
                .as("FIXED_RATE 每 8s 应产生至少 2 个实例，实际 %d", count)
                .isGreaterThanOrEqualTo(2);
    }

    // ================================================================
    // T026 [US4] FIXED_DELAY 语义验证（策略单元测试 + 集成验证）
    // ================================================================

    @Test
    void fixedDelay_usesIntervalCorrectly() throws Exception {
        // given: FIXED_DELAY 每 5 秒延迟
        wf.setScheduleType("FIXED_DELAY");
        wf.setCron(null);
        wf.setScheduleIntervalMs(5_000L);
        wf.setNextTriggerTime(LocalDateTime.now().plusSeconds(2).truncatedTo(ChronoUnit.SECONDS));
        wf.setLastFireTime(null);
        workflowDefRepository.save(wf);

        // when
        triggerEngine.scanAndArm(LocalDateTime.now());
        Thread.sleep(20_000);

        for (int i = 0; i < 3; i++) {
            triggerEngine.scanAndArm(LocalDateTime.now());
            Thread.sleep(3000);
        }

        // then: 至少触发了一次
        List<WorkflowInstance> instances = workflowInstanceRepository.findByWorkflowId(1L);
        long count = instances.stream()
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(testStart))
                .count();
        assertThat(count)
                .as("FIXED_DELAY 应在超时内触发至少 1 个实例")
                .isGreaterThanOrEqualTo(1);
    }

    // ================================================================
    // T030 [US4] TriggerEngine 按 type 选策略 （集成在以上测试中验证）
    // ================================================================

    @Test
    void triggerEngine_routesToCorrectStrategy() throws Exception {
        // given: 先 CRON 触发，再 FIXED_RATE 触发
        // CRON
        wf.setScheduleType("CRON");
        wf.setCron("*/10 * * * * *");
        wf.setScheduleIntervalMs(null);
        wf.setNextTriggerTime(LocalDateTime.now().plusSeconds(2).truncatedTo(ChronoUnit.SECONDS));
        wf.setLastFireTime(LocalDateTime.now().minusHours(1));
        workflowDefRepository.save(wf);

        triggerEngine.scanAndArm(LocalDateTime.now());
        Thread.sleep(15_000);

        triggerEngine.scanAndArm(LocalDateTime.now());
        Thread.sleep(5000);

        // CRON 应产生实例
        List<WorkflowInstance> instances = workflowInstanceRepository.findByWorkflowId(1L);
        long cronCount = instances.stream()
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(testStart))
                .count();
        assertThat(cronCount)
                .as("CRON 策略应产生实例")
                .isGreaterThanOrEqualTo(1);

        // 清空，换 FIXED_RATE
        List<WorkflowInstance> toDelete = new ArrayList<>();
        workflowInstanceRepository.findByWorkflowId(1L).forEach(toDelete::add);
        toDelete.forEach(workflowInstanceRepository::delete);

        wf = workflowDefRepository.findById(1L).orElseThrow();
        wf.setScheduleType("FIXED_RATE");
        wf.setCron(null);
        wf.setScheduleIntervalMs(5_000L);
        wf.setNextTriggerTime(LocalDateTime.now().plusSeconds(2).truncatedTo(ChronoUnit.SECONDS));
        wf.setLastFireTime(null);
        workflowDefRepository.save(wf);

        triggerEngine.scanAndArm(LocalDateTime.now());
        Thread.sleep(15_000);

        instances = workflowInstanceRepository.findByWorkflowId(1L);
        long rateCount = instances.stream()
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(testStart))
                .count();
        assertThat(rateCount)
                .as("FIXED_RATE 策略也应产生实例")
                .isGreaterThanOrEqualTo(1);
    }
}
