package com.dataweave.alert.infrastructure.jdbc;

import com.dataweave.alert.application.AlertRuleService;
import com.dataweave.alert.domain.AlertRule;
import com.dataweave.master.application.ProjectScope;
import com.dataweave.master.domain.ProjectMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 036 US3：告警规则项目隔离——双项目真 H2 验证跨项目 0 串。
 *
 * <p>同租户下两个项目各建规则，断言 {@code findByTenantIdAndProjectId} /
 * {@code countByTenantIdAndProjectId} / {@code AlertRuleService.list} 仅返回本项目数据。
 */
class AlertRuleProjectIsolationTest {

    private AlertRuleJdbcRepository repo;
    private AlertRuleService service;

    private static final long TENANT = 1L;
    private static final long PROJECT_A = 10L;
    private static final long PROJECT_B = 20L;

    @BeforeEach
    void setUp() {
        var ds = new SingleConnectionDataSource(
                "jdbc:h2:mem:alert_iso_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE alert_rule (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    project_id BIGINT,
                    name VARCHAR(256),
                    description VARCHAR(1024),
                    enabled INT DEFAULT 1,
                    signal_source VARCHAR(64),
                    eval_mode VARCHAR(32),
                    eval_interval_sec INT,
                    condition_json VARCHAR(4096),
                    severity VARCHAR(32),
                    for_duration INT DEFAULT 0,
                    dedup_key_template VARCHAR(512),
                    suppress_window_sec INT DEFAULT 0,
                    auto_resolve INT DEFAULT 0,
                    labels_json VARCHAR(2048),
                    created_by BIGINT,
                    updated_by BIGINT,
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP,
                    deleted INT DEFAULT 0,
                    version INT DEFAULT 0
                )
                """);
        repo = new AlertRuleJdbcRepository(jdbc);
        service = new AlertRuleService(repo);

        repo.save(rule("A-rule-1", PROJECT_A));
        repo.save(rule("A-rule-2", PROJECT_A));
        repo.save(rule("B-rule-1", PROJECT_B));
    }

    private AlertRule rule(String name, long projectId) {
        AlertRule r = new AlertRule();
        r.setTenantId(TENANT);
        r.setProjectId(projectId);
        r.setName(name);
        r.setEnabled(1);
        r.setSignalSource("METRIC");
        r.setEvalMode("POLL");
        r.setSeverity("P2");
        r.setCreatedBy(1L);
        return r;
    }

    @Test
    void repo_isolates_rules_by_project() {
        assertThat(repo.findByTenantIdAndProjectId(TENANT, PROJECT_A, 0, 50))
                .extracting(AlertRule::getName)
                .containsExactlyInAnyOrder("A-rule-1", "A-rule-2");
        assertThat(repo.findByTenantIdAndProjectId(TENANT, PROJECT_B, 0, 50))
                .extracting(AlertRule::getName)
                .containsExactly("B-rule-1");

        assertThat(repo.countByTenantIdAndProjectId(TENANT, PROJECT_A)).isEqualTo(2);
        assertThat(repo.countByTenantIdAndProjectId(TENANT, PROJECT_B)).isEqualTo(1);
    }

    @Test
    void service_list_isolates_by_project() {
        assertThat(service.list(TENANT, PROJECT_A, null, null, 0, 50))
                .extracting(AlertRule::getName)
                .containsExactlyInAnyOrder("A-rule-1", "A-rule-2");
        assertThat(service.list(TENANT, PROJECT_B, null, null, 0, 50))
                .hasSize(1);
    }

    @Test
    void service_list_with_signalSource_filter_still_isolates_by_project() {
        // signalSource+enabled 分支：租户级查询后内存按 projectId 过滤，仍不跨项目
        assertThat(service.list(TENANT, PROJECT_A, "METRIC", true, 0, 50))
                .extracting(AlertRule::getName)
                .containsExactlyInAnyOrder("A-rule-1", "A-rule-2");
        assertThat(service.list(TENANT, PROJECT_B, "METRIC", true, 0, 50))
                .extracting(AlertRule::getName)
                .containsExactly("B-rule-1");
    }

    /** ProjectScope 成员校验在此模块可注入（地基被 alert 消费的接缝冒烟）。 */
    @Test
    void projectScope_is_consumable_from_alert_module() {
        ProjectMemberRepository members = mock(ProjectMemberRepository.class);
        when(members.countByTenantIdAndProjectIdAndUserIdAndDeleted(TENANT, PROJECT_A, 5L, 0)).thenReturn(1L);
        ProjectScope scope = new ProjectScope(members);
        assertThat(scope.require(TENANT, 5L, PROJECT_A)).isEqualTo(PROJECT_A);
        assertThat(scope.isMember(TENANT, 5L, PROJECT_B)).isFalse();
    }
}
