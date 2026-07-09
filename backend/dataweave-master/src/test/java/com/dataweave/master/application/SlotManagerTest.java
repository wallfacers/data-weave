package com.dataweave.master.application;

import com.dataweave.master.application.SchedulingPolicy.NodeLoad;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.jdbc.core.RowCallbackHandler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 028/060 SlotManager test。
 *
 * <p>028：NULL 容量→0 槽回归防护。
 * <p>060（T008）：节点可用性门三谓词——心跳不新鲜 / 纪元未过稳定窗 / 隔离中 各被排除，健康节点放行（FR-001/002/003/005）。
 */
class SlotManagerTest {

    private WorkerNodeRepository repo;
    private JdbcTemplate jdbc;
    private SlotManager slotManager;

    @BeforeEach
    void setUp() {
        repo = mock(WorkerNodeRepository.class);
        jdbc = mock(JdbcTemplate.class);
        slotManager = new SlotManager(repo, jdbc, 15000L);  // 060：稳定窗 15s
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

    // ── 060（T008）：节点可用性门三谓词 ──

    @Test
    void staleHeartbeat_excluded() {
        WorkerNode node = nodeWithCapacity(10, 1);
        node.setLastHeartbeat(LocalDateTime.now().minus(Duration.ofSeconds(60)));  // 超过 30s 离线阈值
        when(repo.findByStatus("ONLINE")).thenReturn(List.of(node));
        doNothing().when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        assertThat(slotManager.availableForNormal()).isEmpty();  // 心跳不新鲜 → 排除
    }

    @Test
    void unstableIncarnation_excluded() {
        WorkerNode node = nodeWithCapacity(10, 1);
        node.setIncarnationSince(LocalDateTime.now());  // 刚重启，纪元未过 15s 稳定窗
        when(repo.findByStatus("ONLINE")).thenReturn(List.of(node));
        doNothing().when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        assertThat(slotManager.availableForNormal()).isEmpty();  // 纪元未过稳定窗 → 排除
    }

    @Test
    void quarantined_excluded() {
        WorkerNode node = nodeWithCapacity(10, 1);
        node.setQuarantinedUntil(LocalDateTime.now().plus(Duration.ofMinutes(1)));  // 隔离中
        when(repo.findByStatus("ONLINE")).thenReturn(List.of(node));
        doNothing().when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        assertThat(slotManager.availableForNormal()).isEmpty();  // 隔离中 → 排除
    }

    @Test
    void quarantinedExpired_passes() {
        WorkerNode node = nodeWithCapacity(10, 1);
        node.setQuarantinedUntil(LocalDateTime.now().minus(Duration.ofSeconds(1)));  // 隔离已到期
        when(repo.findByStatus("ONLINE")).thenReturn(List.of(node));
        doNothing().when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        assertThat(slotManager.availableForNormal()).hasSize(1);  // 隔离到期 → 回归候选
    }

    @Test
    void healthyNode_passed_snapshotOnlineSameSource() {
        WorkerNode healthy = nodeWithCapacity(10, 1);  // 新鲜心跳 + 无纪元/隔离
        when(repo.findByStatus("ONLINE")).thenReturn(List.of(healthy));
        doNothing().when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        assertThat(slotManager.availableForNormal()).hasSize(1);
        assertThat(slotManager.snapshotOnline()).hasSize(1);  // 同源过滤
    }

    private static WorkerNode nodeWithCapacity(Integer max, Integer reserved) {
        WorkerNode n = new WorkerNode();
        n.setNodeCode("w1");
        n.setMaxConcurrentTasks(max);
        n.setReservedTestSlots(reserved);
        n.setStatus("ONLINE");
        n.setLastHeartbeat(LocalDateTime.now());  // 060：默认新鲜心跳，使健康节点通过可用性门
        return n;
    }
}
