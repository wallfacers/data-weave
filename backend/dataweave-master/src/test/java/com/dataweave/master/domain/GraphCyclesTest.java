package com.dataweave.master.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GraphCyclesTest {

    @Test
    void acyclicDag_hasNoCycle() {
        // 1→2, 1→3, 2→4, 3→4
        Map<Integer, List<Integer>> g = Map.of(
                1, List.of(2, 3),
                2, List.of(4),
                3, List.of(4));
        assertThat(GraphCycles.findCycle(g)).isEmpty();
    }

    @Test
    void simpleCycle_isDetected() {
        // 1→2→3→1
        Map<Integer, List<Integer>> g = Map.of(
                1, List.of(2),
                2, List.of(3),
                3, List.of(1));
        Optional<List<Integer>> cycle = GraphCycles.findCycle(g);
        assertThat(cycle).isPresent();
        // 路径首尾相同，闭合环
        List<Integer> c = cycle.get();
        assertThat(c.get(0)).isEqualTo(c.get(c.size() - 1));
        assertThat(c).contains(1, 2, 3);
    }

    @Test
    void selfLoop_isDetected() {
        Map<Integer, List<Integer>> g = Map.of(1, List.of(1));
        assertThat(GraphCycles.findCycle(g)).isPresent();
    }

    @Test
    void disjointComponents_oneCyclic_isDetected() {
        Map<Integer, List<Integer>> g = Map.of(
                1, List.of(2),       // 无环组件
                10, List.of(11),
                11, List.of(10));    // 有环组件
        assertThat(GraphCycles.findCycle(g)).isPresent();
    }

    @Test
    void emptyGraph_hasNoCycle() {
        assertThat(GraphCycles.findCycle(Map.<Integer, List<Integer>>of())).isEmpty();
    }
}
