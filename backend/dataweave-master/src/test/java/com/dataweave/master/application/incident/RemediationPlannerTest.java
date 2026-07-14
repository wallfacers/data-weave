package com.dataweave.master.application.incident;

import com.dataweave.master.domain.incident.IncidentClassifications;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 069 T020/T024：RemediationPlanner 确定性梯度映射全分支单测（纯函数，无需 Spring 上下文）。
 */
class RemediationPlannerTest {

    private final RemediationPlanner planner = new RemediationPlanner(16384L, 2.0, 3);

    @Test
    void transientClassificationReruns() {
        var d = planner.plan(IncidentClassifications.TRANSIENT, 0, false, false, null, null);
        assertThat(d.kind()).isEqualTo(RemediationPlanner.ActionKind.RERUN);
    }

    @Test
    void resourceClassificationBumpsWithinGuard() {
        var d = planner.plan(IncidentClassifications.RESOURCE, 0, false, false, 2048, 2);
        assertThat(d.kind()).isEqualTo(RemediationPlanner.ActionKind.ADJUST_RESOURCES);
        assertThat(d.newMemoryMb()).isEqualTo(4096);
        assertThat(d.newCpuCores()).isEqualTo(4);
    }

    @Test
    void resourceClassificationEscalatesWhenBumpExceedsMemoryCap() {
        // 8192 * 2.0 = 16384，等于上限不算超；调到 10000 * 2.0=20000 才越界
        var d = planner.plan(IncidentClassifications.RESOURCE, 0, false, false, 10000, 4);
        assertThat(d.kind()).isEqualTo(RemediationPlanner.ActionKind.ESCALATE);
    }

    @Test
    void resourceClassificationEscalatesWhenStepFactorIsAtMostOne() {
        RemediationPlanner noStepPlanner = new RemediationPlanner(16384L, 1.0, 3);
        var d = noStepPlanner.plan(IncidentClassifications.RESOURCE, 0, false, false, 2048, 2);
        assertThat(d.kind()).isEqualTo(RemediationPlanner.ActionKind.ESCALATE);
    }

    @Test
    void codeClassificationProposesFix() {
        var d = planner.plan(IncidentClassifications.CODE, 0, false, false, null, null);
        assertThat(d.kind()).isEqualTo(RemediationPlanner.ActionKind.PROPOSE_FIX);
    }

    @Test
    void upstreamDataEscalatesImmediately() {
        var d = planner.plan(IncidentClassifications.UPSTREAM_DATA, 0, false, false, null, null);
        assertThat(d.kind()).isEqualTo(RemediationPlanner.ActionKind.ESCALATE);
    }

    @Test
    void credentialEscalatesImmediately() {
        var d = planner.plan(IncidentClassifications.CONFIG_CREDENTIAL, 0, false, false, null, null);
        assertThat(d.kind()).isEqualTo(RemediationPlanner.ActionKind.ESCALATE);
    }

    @Test
    void unknownProbesOnceThenEscalates() {
        var first = planner.plan(IncidentClassifications.UNKNOWN, 0, false, false, null, null);
        assertThat(first.kind()).isEqualTo(RemediationPlanner.ActionKind.RERUN);

        var second = planner.plan(IncidentClassifications.UNKNOWN, 1, false, false, null, null);
        assertThat(second.kind()).isEqualTo(RemediationPlanner.ActionKind.ESCALATE);
    }

    @Test
    void streamingWithCheckpointPrefersResumeOverClassification() {
        // 即使分型是 TRANSIENT，只要有检查点可用，优先续跑（覆盖分型判断）
        var d = planner.plan(IncidentClassifications.TRANSIENT, 0, true, true, null, null);
        assertThat(d.kind()).isEqualTo(RemediationPlanner.ActionKind.RESUME_CHECKPOINT);
    }

    @Test
    void streamingWithoutCheckpointFallsBackToClassification() {
        var d = planner.plan(IncidentClassifications.TRANSIENT, 0, true, false, null, null);
        assertThat(d.kind()).isEqualTo(RemediationPlanner.ActionKind.RERUN);
    }

    @Test
    void exceedingMaxAutoActionsForcesEscalation() {
        var d = planner.plan(IncidentClassifications.TRANSIENT, 3, false, false, null, null);
        assertThat(d.kind()).isEqualTo(RemediationPlanner.ActionKind.ESCALATE);
        assertThat(d.reason()).contains("上限");
    }

    @Test
    void nullClassificationEscalates() {
        var d = planner.plan(null, 0, false, false, null, null);
        assertThat(d.kind()).isEqualTo(RemediationPlanner.ActionKind.ESCALATE);
    }
}
