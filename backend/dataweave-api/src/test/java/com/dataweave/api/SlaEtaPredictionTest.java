package com.dataweave.api;

import com.dataweave.master.application.SlaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ETA 预测回归（task 5.4 / 5.6）：历史运行时长中位数 + 运行中实例最迟完成预测、冷启动留空。
 *
 * <p>用唯一 task_id（不与 data.sql 种子碰撞）插入受控实例，规避共享 h2 库污染；
 * predictLatestEta 用「超大时长」让本测试实例成为全局最迟，从而对 remainingSeconds 做确定性断言。
 */
@SpringBootTest
@ActiveProfiles("h2")
class SlaEtaPredictionTest {

    @Autowired
    SlaService slaService;
    @Autowired
    JdbcTemplate jdbc;

    private void insertSuccess(long taskId, LocalDateTime started, LocalDateTime finished) {
        jdbc.update(
                "INSERT INTO task_instance (id, tenant_id, project_id, task_id, run_mode, state, "
                        + "started_at, finished_at, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, 'NORMAL', 'SUCCESS', ?, ?, ?, ?, 0, 0)",
                UUID.randomUUID(), taskId, started, finished, started, finished);
    }

    private void insertRunning(long taskId, LocalDateTime started) {
        jdbc.update(
                "INSERT INTO task_instance (id, tenant_id, project_id, task_id, run_mode, state, "
                        + "started_at, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, 'NORMAL', 'RUNNING', ?, ?, ?, 0, 0)",
                UUID.randomUUID(), taskId, started, started, started);
    }

    @Test
    void durationMedianMs_奇数样本取中间值() {
        long taskId = 880001L;
        LocalDateTime base = LocalDateTime.of(2026, 6, 20, 2, 0);
        insertSuccess(taskId, base, base.plusMinutes(10)); // 10min
        insertSuccess(taskId, base, base.plusMinutes(20)); // 20min ← 中位
        insertSuccess(taskId, base, base.plusMinutes(30)); // 30min

        Long median = slaService.durationMedianMs(taskId, 7);
        assertThat(median).isEqualTo(20L * 60 * 1000);
    }

    @Test
    void durationMedianMs_偶数样本取中间两值均值() {
        long taskId = 880002L;
        LocalDateTime base = LocalDateTime.of(2026, 6, 20, 2, 0);
        insertSuccess(taskId, base, base.plusMinutes(10));
        insertSuccess(taskId, base, base.plusMinutes(20));
        insertSuccess(taskId, base, base.plusMinutes(30));
        insertSuccess(taskId, base, base.plusMinutes(40));

        Long median = slaService.durationMedianMs(taskId, 7); // (20+30)/2 = 25min
        assertThat(median).isEqualTo(25L * 60 * 1000);
    }

    @Test
    void durationMedianMs_冷启动无成功样本返回null() {
        assertThat(slaService.durationMedianMs(999999L, 7)).isNull();
    }

    @Test
    void predictLatestEta_取运行中实例最迟完成_有样本则非空() {
        long taskId = 880003L;
        LocalDateTime base = LocalDateTime.of(2026, 6, 20, 2, 0);
        // 超大历史时长（100min），保证本实例预计完成晚于任何 data.sql 种子运行实例 → 成全局最迟。
        insertSuccess(taskId, base, base.plusMinutes(100));
        insertSuccess(taskId, base, base.plusMinutes(100));
        insertSuccess(taskId, base, base.plusMinutes(100));
        insertRunning(taskId, LocalDateTime.now()); // 此刻起跑 → 预计 +100min

        SlaService.EtaResult eta = slaService.predictLatestEta(1L, 1L);
        assertThat(eta).as("有运行中+历史样本应给出预测").isNotNull();
        assertThat(eta.predictedCount()).isGreaterThanOrEqualTo(1);
        assertThat(eta.runningCount()).isGreaterThanOrEqualTo(1);
        // 本实例 100min 起跑于 now，剩余应接近 6000s（容忍测试执行耗时与其他实例不超过它）。
        assertThat(eta.remainingSeconds()).isBetween(5900L, 6000L);
    }
}
