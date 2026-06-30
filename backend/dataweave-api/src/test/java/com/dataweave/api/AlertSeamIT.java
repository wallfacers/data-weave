package com.dataweave.api;

import com.dataweave.alert.domain.AlertEvent;
import com.dataweave.alert.domain.repository.AlertEventRepository;
import com.dataweave.master.domain.signal.AlertSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 告警引擎接缝测试（021）：master publish {@link AlertSignal} → alert 侧 {@code AlertSignalListener}
 * 消费 → 规则匹配 → {@code AlertLifecycleService} 建 {@code alert_event}（FIRING）。
 *
 * <p>独立 H2 库隔离；同步 @EventListener，publish 后即处理。证明信号→事件链路通（SC 接缝闭环）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-alertseam;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@DisplayName("告警引擎接缝 (021)")
class AlertSeamIT {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    AlertEventRepository alertEventRepository;

    @Test
    @DisplayName("TASK_FAILED 信号 → 命中规则 → alert_event 落库为 FIRING")
    void taskFailedSignalCreatesAlertEvent() {
        // 1) 插一条 EVENT 模式、TASK_INSTANCE 源、无附加条件（匹配任意同源信号）的启用规则
        jdbc.update("INSERT INTO alert_rule (tenant_id, name, enabled, signal_source, eval_mode, "
                        + "condition_json, severity, for_duration, suppress_window_sec, auto_resolve, "
                        + "created_at, updated_at, deleted, version) "
                        + "VALUES (?, ?, 1, 'TASK_INSTANCE', 'EVENT', NULL, 'P2', 1, 300, 1, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)",
                1L, "任务失败即告警(接缝测试)");

        long before = count();

        // 2) master 侧发出信号（模拟 InstanceStateMachine 终态发射）
        publisher.publishEvent(new AlertSignal(
                AlertSignal.Type.TASK_FAILED, 1L, "task-99", "P2",
                Map.of("taskInstanceId", "019f0000-0000-7000-8000-000000000099",
                        "taskId", 99, "failureReason", "boom")));

        // 3) 同步监听已处理 → 新增一条 FIRING 事件
        long after = count();
        assertThat(after).as("应新建 1 条 FIRING alert_event").isEqualTo(before + 1);
    }

    /**
     * 回归：{@code save()} 必须回取自增主键。原实现用 H2 1.x 遗留的 {@code CALL IDENTITY()}，
     * 在 H2 2.x（Function "identity" not found）/PostgreSQL 上 INSERT 已自动提交、id 回取却抛错——
     * seam 测试只数行数无法察觉（行落了但返回实体 id=null）。此处直测仓储，锁定 id 必被填充。
     */
    @Test
    @DisplayName("save() 回取自增主键（防 CALL IDENTITY() 退化）")
    void saveReturnsGeneratedId() {
        AlertEvent e = new AlertEvent();
        e.setTenantId(1L);
        e.setRuleId(1L);
        e.setState("FIRING");
        e.setSeverity("P2");
        e.setFingerprint("seam-id-probe");
        e.setCount(1);
        e.setFirstFiredAt(LocalDateTime.now());
        e.setLastFiredAt(LocalDateTime.now());

        AlertEvent saved = alertEventRepository.save(e);

        assertThat(saved.getId()).as("save() 应回取自增主键，非 null").isNotNull();
        assertThat(saved.getId()).as("自增主键应为正").isPositive();
        assertThat(alertEventRepository.findById(saved.getId()))
                .as("按回取的 id 应能查回该事件").isPresent();
    }

    private long count() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_event WHERE tenant_id = 1 AND state = 'FIRING'", Long.class);
        return n == null ? 0L : n;
    }
}
