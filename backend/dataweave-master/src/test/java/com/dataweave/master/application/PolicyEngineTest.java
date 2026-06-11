package com.dataweave.master.application;

import com.dataweave.master.domain.PolicyRule;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * PolicyEngine 分级矩阵、归属/环境/数量抬升、命令串安全解析。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PolicyEngineTest {

    @Mock
    private com.dataweave.master.domain.PolicyRuleRepository ruleRepository;
    @Mock
    private TaskInstanceRepository instanceRepository;
    @Mock
    private TaskDefRepository taskDefRepository;

    private PolicyEngine engine(String env, int batchThreshold) {
        when(ruleRepository.findByEnabledOrderBySortOrderAscIdAsc(1)).thenReturn(seedRules());
        return new PolicyEngine(ruleRepository, instanceRepository, taskDefRepository, env, batchThreshold);
    }

    private List<PolicyRule> seedRules() {
        List<PolicyRule> rules = new ArrayList<>();
        rules.add(rule("CMD_PREFIX", "rm", "L4", 5));
        rules.add(rule("CMD_PREFIX", "df", "L0", 10));
        rules.add(rule("CMD_PREFIX", "tail", "L0", 10));
        rules.add(rule("CMD_PREFIX", "grep", "L0", 10));
        rules.add(rule("CMD_PREFIX", "dw logs", "L0", 10));
        rules.add(rule("CMD_PREFIX", "dw task rerun", "L1", 20));
        rules.add(rule("TOOL", "query_task_instances", "L0", 10));
        rules.add(rule("TOOL", "task_rerun", "L1", 20));
        rules.add(rule("TOOL", "apply_fix", "L1", 20));
        rules.add(rule("TOOL", "node_exec", "L1", 25));
        rules.add(rule("TOOL", "drop_table", "L3", 30));
        return rules;
    }

    private PolicyRule rule(String type, String pattern, String level, int sort) {
        PolicyRule r = new PolicyRule();
        r.setMatchType(type);
        r.setPattern(pattern);
        r.setBaseLevel(level);
        r.setEnabled(1);
        r.setSortOrder(sort);
        return r;
    }

    @Test
    void readTool_isL0_execute() {
        PolicyDecision d = engine("dev", 50).decide(
                ActionRequest.builder().toolName("query_task_instances").build());
        assertThat(d.level()).isEqualTo(PolicyLevel.L0);
        assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.EXECUTE);
    }

    @Test
    void reversibleWriteTool_ownedPlatform_isL1_execute() {
        PolicyDecision d = engine("dev", 50).decide(
                ActionRequest.builder().toolName("task_rerun")
                        .targetType("TASK_INSTANCE").targetId("01910000-0010-7000-8000-000000000100")
                        .ownedByPlatform(true).build());
        assertThat(d.level()).isEqualTo(PolicyLevel.L1);
        assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.EXECUTE);
    }

    @Test
    void ownershipNotPlatform_escalatesL1ToL2_approval() {
        PolicyDecision d = engine("dev", 50).decide(
                ActionRequest.builder().toolName("task_rerun")
                        .targetType("APP").targetId("application_x")
                        .ownedByPlatform(false).build());
        assertThat(d.level()).isEqualTo(PolicyLevel.L2);
        assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.PENDING_APPROVAL);
        assertThat(d.reasons()).anyMatch(r -> r.contains("不归属本平台"));
    }

    @Test
    void prodEnvironment_escalatesL1ToL2() {
        PolicyDecision d = engine("prod", 50).decide(
                ActionRequest.builder().toolName("task_rerun")
                        .ownedByPlatform(true).build());
        assertThat(d.level()).isEqualTo(PolicyLevel.L2);
        assertThat(d.reasons()).anyMatch(r -> r.contains("prod"));
    }

    @Test
    void batchOverThreshold_escalatesL1ToL2() {
        PolicyDecision d = engine("dev", 50).decide(
                ActionRequest.builder().toolName("task_rerun")
                        .ownedByPlatform(true).batchCount(200).build());
        assertThat(d.level()).isEqualTo(PolicyLevel.L2);
        assertThat(d.reasons()).anyMatch(r -> r.contains("批量"));
    }

    @Test
    void ownershipResolvedFromDb_whenNotGiven() {
        when(instanceRepository.findById(java.util.UUID.fromString("01910000-0010-7000-8000-000000000100"))).thenReturn(Optional.of(new TaskInstance()));
        PolicyDecision owned = engine("dev", 50).decide(
                ActionRequest.builder().toolName("task_rerun")
                        .targetType("TASK_INSTANCE").targetId("01910000-0010-7000-8000-000000000100").build());
        assertThat(owned.level()).isEqualTo(PolicyLevel.L1);

        when(instanceRepository.findById(java.util.UUID.fromString("01910000-0010-7000-8000-000000000999"))).thenReturn(Optional.empty());
        PolicyDecision foreign = engine("dev", 50).decide(
                ActionRequest.builder().toolName("task_rerun")
                        .targetType("TASK_INSTANCE").targetId("01910000-0010-7000-8000-000000000999").build());
        assertThat(foreign.level()).isEqualTo(PolicyLevel.L2);
    }

    @Test
    void pipeFilter_doesNotEscalate() {
        PolicyDecision d = engine("dev", 50).decide(
                ActionRequest.builder().command("dw logs cat 100 | grep -i oom | tail -50").build());
        assertThat(d.level()).isEqualTo(PolicyLevel.L0);
        assertThat(d.injectionDetected()).isFalse();
    }

    @Test
    void redirect_escalatesToL2() {
        PolicyDecision d = engine("dev", 50).decide(
                ActionRequest.builder().command("df -h > /tmp/out.txt").build());
        assertThat(d.injectionDetected()).isTrue();
        assertThat(d.level()).isEqualTo(PolicyLevel.L2);
        assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.PENDING_APPROVAL);
    }

    @Test
    void commandSeparatorAndSubshell_escalateToL2() {
        assertThat(engine("dev", 50).decide(
                ActionRequest.builder().command("df -h ; rm -rf /tmp").build()).level())
                .isEqualTo(PolicyLevel.L2);
        assertThat(engine("dev", 50).decide(
                ActionRequest.builder().command("tail -50 a.log && echo done").build()).level())
                .isEqualTo(PolicyLevel.L2);
        assertThat(engine("dev", 50).decide(
                ActionRequest.builder().command("df $(whoami)").build()).injectionDetected())
                .isTrue();
    }

    @Test
    void forbiddenCommand_isL4_rejected() {
        PolicyDecision d = engine("dev", 50).decide(
                ActionRequest.builder().command("rm -rf /data").build());
        assertThat(d.level()).isEqualTo(PolicyLevel.L4);
        assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.REJECTED);
    }

    @Test
    void unmatchedWriteTool_defaultsToL2() {
        PolicyDecision d = engine("dev", 50).decide(
                ActionRequest.builder().toolName("some_unknown_write_tool").build());
        assertThat(d.level()).isEqualTo(PolicyLevel.L2);
    }

    @Test
    void l3Tool_requiresConfirmation() {
        PolicyDecision d = engine("dev", 50).decide(
                ActionRequest.builder().toolName("drop_table").targetId("dwd_orders").build());
        assertThat(d.level()).isEqualTo(PolicyLevel.L3);
        assertThat(d.requiresConfirmation()).isTrue();
        assertThat(d.outcome()).isEqualTo(PolicyDecision.Outcome.PENDING_APPROVAL);
    }

    // 仅为消除未使用告警占位
    @SuppressWarnings("unused")
    private TaskDef placeholder() {
        return new TaskDef();
    }
}
