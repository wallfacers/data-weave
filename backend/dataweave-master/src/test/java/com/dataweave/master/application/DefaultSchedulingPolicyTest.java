package com.dataweave.master.application;

import com.dataweave.master.application.SchedulingPolicy.Candidate;
import com.dataweave.master.application.SchedulingPolicy.NodeLoad;
import com.dataweave.master.domain.WorkerNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSchedulingPolicyTest {

    private final DefaultSchedulingPolicy policy = new DefaultSchedulingPolicy(60);
    private final LocalDateTime now = LocalDateTime.of(2026, 6, 11, 12, 0, 0);

    private Candidate cand(int declared, LocalDateTime waitingSince, boolean test) {
        return new Candidate(UUID.randomUUID(), declared, waitingSince, test);
    }

    private NodeLoad node(String code, double load, int used, int cap) {
        WorkerNode w = new WorkerNode();
        w.setNodeCode(code);
        w.setLoadAvg(load);
        return new NodeLoad(w, used, cap);
    }

    @Test
    void freshCandidate_effectivePriorityEqualsDeclared() {
        assertThat(policy.effectivePriority(cand(5, now, false), now)).isEqualTo(5);
    }

    @Test
    void aging_liftsPriorityWithWaitTime() {
        // 等待 180s，agingStep=60 → 抬 3 档：5 - 3 = 2（数值越小越优先）
        Candidate aged = cand(5, now.minusSeconds(180), false);
        assertThat(policy.effectivePriority(aged, now)).isEqualTo(2);
    }

    @Test
    void aging_neverGoesBelowZero() {
        Candidate veryOld = cand(1, now.minusHours(10), false);
        assertThat(policy.effectivePriority(veryOld, now)).isEqualTo(0);
    }

    @Test
    void testRun_getsExtraPriorityBump() {
        int normal = policy.effectivePriority(cand(5, now, false), now);
        int test = policy.effectivePriority(cand(5, now, true), now);
        assertThat(test).isLessThan(normal);
    }

    @Test
    void place_picksLeastLoaded_mostFreeSlots() {
        NodeLoad a = node("a", 1.0, 8, 10);  // free 2
        NodeLoad b = node("b", 1.0, 3, 10);  // free 7
        NodeLoad full = node("c", 0.1, 10, 10); // free 0
        assertThat(policy.place(cand(5, now, false), List.of(a, b, full)))
                .get().extracting(n -> n.node().getNodeCode()).isEqualTo("b");
    }

    @Test
    void place_tieOnFreeSlots_prefersLowerLoad() {
        NodeLoad a = node("a", 9.0, 5, 10);  // free 5, high load
        NodeLoad b = node("b", 1.0, 5, 10);  // free 5, low load
        assertThat(policy.place(cand(5, now, false), List.of(a, b)))
                .get().extracting(n -> n.node().getNodeCode()).isEqualTo("b");
    }

    @Test
    void place_noFreeSlots_returnsEmpty() {
        assertThat(policy.place(cand(5, now, false), List.of(node("a", 1.0, 10, 10)))).isEmpty();
    }
}
