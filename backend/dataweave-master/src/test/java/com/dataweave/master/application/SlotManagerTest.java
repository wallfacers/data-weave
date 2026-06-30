package com.dataweave.master.application;

import com.dataweave.master.application.SchedulingPolicy.NodeLoad;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 028: SlotManager test — NULL 容量→0 槽回归防护。
 *
 * <p>Verifies that a worker_node row with NULL maxConcurrentTasks
 * (possible if FleetService.report() default-fill is bypassed)
 * is treated as 0 capacity, not NPE or negative.
 */
class SlotManagerTest {

    private WorkerNodeRepository repo;
    private JdbcTemplate jdbc;
    private SlotManager slotManager;

    @BeforeEach
    void setUp() {
        repo = mock(WorkerNodeRepository.class);
        jdbc = mock(JdbcTemplate.class);
        slotManager = new SlotManager(repo, jdbc);
    }

    @Test
    void nullCapacity_treatedAsZero() {
        WorkerNode node = nodeWithCapacity(null, null);
        when(repo.findByStatus("ONLINE")).thenReturn(List.of(node));
        doNothing().when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        List<NodeLoad> loads = slotManager.availableForNormal();
        assertThat(loads).hasSize(1);
        assertThat(loads.get(0).capacity()).isEqualTo(0);
    }

    @Test
    void normalCapacity_excludesReservedSlots() {
        WorkerNode node = nodeWithCapacity(10, 3);
        when(repo.findByStatus("ONLINE")).thenReturn(List.of(node));
        doNothing().when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        List<NodeLoad> loads = slotManager.availableForNormal();
        assertThat(loads.get(0).capacity()).isEqualTo(7); // 10 - 3
    }

    @Test
    void testCapacity_includesReservedSlots() {
        WorkerNode node = nodeWithCapacity(10, 3);
        when(repo.findByStatus("ONLINE")).thenReturn(List.of(node));
        doNothing().when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        List<NodeLoad> loads = slotManager.availableForTest();
        assertThat(loads.get(0).capacity()).isEqualTo(10); // all slots
    }

    private static WorkerNode nodeWithCapacity(Integer max, Integer reserved) {
        WorkerNode n = new WorkerNode();
        n.setNodeCode("w1");
        n.setMaxConcurrentTasks(max);
        n.setReservedTestSlots(reserved);
        n.setStatus("ONLINE");
        return n;
    }
}
