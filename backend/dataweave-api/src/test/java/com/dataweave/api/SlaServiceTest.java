package com.dataweave.api;

import com.dataweave.master.application.SlaService;
import com.dataweave.master.domain.SlaBaseline;
import com.dataweave.master.domain.SlaBaselineRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA 基线记录回归（task-core-capabilities 验收缺口 2）。
 *
 * <p>recordCompletion 的 SQL 曾误查 {@code workflow_instance.run_mode}——该列不存在
 *（run_mode 属 task_instance），H2/PG 均报 bad SQL grammar → 基线记录每次失败、SLA 失效。
 * 修复后 SQL 不再查 run_mode，基线应正常落库。
 */
@SpringBootTest
@ActiveProfiles("h2")
class SlaServiceTest {

    @Autowired
    SlaService slaService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SlaBaselineRepository slaBaselineRepository;

    @Test
    void recordCompletion_记录就绪基线_不再查workflow_instance的run_mode列() {
        UUID wiId = UUID.randomUUID();
        String bizDate = "2026-06-18";
        LocalDateTime now = LocalDateTime.now();
        // workflow_id=1：data.sql 种子「每日 GMV 工作流」。
        jdbc.update(
                "INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, biz_date, "
                        + "started_at, finished_at, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, 1, 'SUCCESS', ?, ?, ?, ?, ?, 0, 0)",
                wiId, bizDate, now, now, now, now);

        // 修复前：抛 bad SQL grammar（列 wi.run_mode 不存在），catch 后仅 warn，基线不落库。
        slaService.recordCompletion(wiId);

        SlaBaseline baseline = slaBaselineRepository
                .findByWorkflowIdAndBizDate(1L, bizDate).orElse(null);
        assertThat(baseline).as("SLA 基线应成功记录").isNotNull();
        assertThat(baseline.getReadyAt()).isNotNull();
    }
}
