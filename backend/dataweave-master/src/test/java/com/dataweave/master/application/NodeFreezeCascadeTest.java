package com.dataweave.master.application;

import com.dataweave.master.domain.TaskInstance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 节点冻结级联语义（ops-center-publish-boundary）的纯函数单测：
 * 下游闭包（含弱依赖边）+ SKIPPED 参与工作流聚合。
 */
class NodeFreezeCascadeTest {

    /** forward 图：V→A→C，V→B→D。冻 A 只跳 A、C；B、D 不受影响。 */
    @Test
    void 冻结中间节点只级联其下游() {
        Map<String, List<String>> forward = Map.of(
                "V", List.of("A", "B"),
                "A", List.of("C"),
                "B", List.of("D"));
        Set<String> closure = WorkflowTriggerService.downstreamClosure(Set.of("A"), forward);
        assertThat(closure).containsExactlyInAnyOrder("A", "C");
        assertThat(closure).doesNotContain("B", "D", "V");
    }

    /** 冻根锚点 V：全图后继闭包都跳。 */
    @Test
    void 冻结根锚点整条跳过() {
        Map<String, List<String>> forward = Map.of(
                "V", List.of("A", "B"),
                "A", List.of("C"),
                "B", List.of("D"));
        Set<String> closure = WorkflowTriggerService.downstreamClosure(Set.of("V"), forward);
        assertThat(closure).containsExactlyInAnyOrder("V", "A", "B", "C", "D");
    }

    /** 叶子节点无下游：只跳自身。 */
    @Test
    void 冻结叶子只跳自身() {
        Map<String, List<String>> forward = Map.of("A", List.of("C"));
        Set<String> closure = WorkflowTriggerService.downstreamClosure(Set.of("C"), forward);
        assertThat(closure).containsExactly("C");
    }

    /**
     * 级联穿透弱依赖：closure 不分边强弱，A→C（无论强弱）都被纳入。
     * downstreamClosure 本身不读 strength，证明冻结优先于依赖强弱（下游一律跳）。
     */
    @Test
    void 级联穿透弱依赖边() {
        Map<String, List<String>> forward = Map.of("A", List.of("C")); // 该边即便是 WEAK
        Set<String> closure = WorkflowTriggerService.downstreamClosure(Set.of("A"), forward);
        assertThat(closure).contains("A", "C");
    }

    /** SKIPPED 与 SUCCESS 同归「已结算」：全 SKIPPED/SUCCESS → 工作流 SUCCESS（不悬挂）。 */
    @Test
    void 全部跳过或成功聚合为成功() {
        WorkflowStateService svc = new WorkflowStateService(null, null, null);
        String state = svc.aggregate(List.of(
                node("SKIPPED"), node("SUCCESS"), node("SKIPPED")));
        assertThat(state).isEqualTo("SUCCESS");
    }

    /** 含 SKIPPED 且仍有 WAITING → RUNNING（推进中，SKIPPED 不阻塞收尾也不算失败）。 */
    @Test
    void 跳过加等待聚合为运行中() {
        WorkflowStateService svc = new WorkflowStateService(null, null, null);
        String state = svc.aggregate(List.of(
                node("SKIPPED"), node("SUCCESS"), node("WAITING")));
        assertThat(state).isEqualTo("RUNNING");
    }

    private static TaskInstance node(String state) {
        TaskInstance ti = new TaskInstance();
        ti.setRunMode("NORMAL");
        ti.setState(state);
        return ti;
    }
}
