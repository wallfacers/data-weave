package com.dataweave.api;

import com.dataweave.master.application.timing.CronTimingStrategy;
import com.dataweave.master.domain.WorkflowDef;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CronTimingStrategy 纯单元回归（无 Spring）：验证 Spring CronExpression 6 字段原生支持秒级
 * （distributed-cron-trigger US4/FR-009），下一次触发计算严格大于 base、无累计漂移，非法表达式返回 null。
 */
class CronTimingStrategyTest {

    private final CronTimingStrategy strategy = new CronTimingStrategy();

    private WorkflowDef cron(String expr) {
        WorkflowDef wf = new WorkflowDef();
        wf.setId(1L);
        wf.setScheduleType("CRON");
        wf.setCron(expr);
        return wf;
    }

    @Test
    void supports_onlyCron() {
        assertThat(strategy.supports("CRON")).isTrue();
        assertThat(strategy.supports("cron")).isTrue();
        assertThat(strategy.supports("FIXED_RATE")).isFalse();
        assertThat(strategy.supports("MANUAL")).isFalse();
    }

    @Test
    void minuteLevelCron_nextIsStrictlyAfterBase() {
        WorkflowDef wf = cron("0 * * * * *");                 // 每分钟第 0 秒
        LocalDateTime base = LocalDateTime.of(2026, 6, 26, 8, 0, 30);
        LocalDateTime next = strategy.next(wf, base);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 6, 26, 8, 1, 0));
        assertThat(next).isAfter(base);
    }

    @Test
    void secondLevelCron_every30s_noDrift() {
        WorkflowDef wf = cron("*/30 * * * * *");              // 每 30 秒（秒级，6 字段）
        LocalDateTime base = LocalDateTime.of(2026, 6, 26, 8, 0, 0);
        LocalDateTime n1 = strategy.next(wf, base);
        LocalDateTime n2 = strategy.next(wf, n1);
        LocalDateTime n3 = strategy.next(wf, n2);
        assertThat(n1).isEqualTo(LocalDateTime.of(2026, 6, 26, 8, 0, 30));
        assertThat(n2).isEqualTo(LocalDateTime.of(2026, 6, 26, 8, 1, 0));
        assertThat(n3).isEqualTo(LocalDateTime.of(2026, 6, 26, 8, 1, 30));
        // 相邻间隔恒为 30s，无累计漂移（SC-005）
        assertThat(java.time.Duration.between(n1, n2).getSeconds()).isEqualTo(30);
        assertThat(java.time.Duration.between(n2, n3).getSeconds()).isEqualTo(30);
    }

    @Test
    void invalidCron_returnsNull() {
        assertThat(strategy.next(cron("not a cron"), LocalDateTime.now())).isNull();
        assertThat(strategy.next(cron(""), LocalDateTime.now())).isNull();
        assertThat(strategy.next(cron(null), LocalDateTime.now())).isNull();
    }
}
