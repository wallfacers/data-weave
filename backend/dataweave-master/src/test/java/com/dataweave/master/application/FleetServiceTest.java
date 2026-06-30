package com.dataweave.master.application;

import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FleetServiceTest {

    @Mock
    private WorkerNodeRepository repository;

    @Mock
    private InstanceStateMachine stateMachine;

    private FleetService fleetService;

    @BeforeEach
    void setUp() {
        fleetService = new FleetService(repository, stateMachine);
    }

    @Test
    void report_newNode_shouldSaveAsOnline() {
        when(repository.findByNodeCode("node-1")).thenReturn(Optional.empty());
        when(repository.save(any(WorkerNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerNode result = fleetService.report("node-1", "host-1", "4C/8G",
                0.3, 0.45, 0.5, 1.2, 0, null, null, 120);

        assertThat(result.getStatus()).isEqualTo("ONLINE");
        assertThat(result.getNodeCode()).isEqualTo("node-1");
        assertThat(result.getHost()).isEqualTo("host-1");
        assertThat(result.getCapacity()).isEqualTo("4C/8G");
        assertThat(result.getCpu()).isEqualTo(0.3);
        assertThat(result.getMem()).isEqualTo(0.45);
        assertThat(result.getDisk()).isEqualTo(0.5);
        assertThat(result.getLoadAvg()).isEqualTo(1.2);
        assertThat(result.getRunningTasks()).isEqualTo(0);
        assertThat(result.getLastHeartbeat()).isNotNull();
        verify(repository).save(any(WorkerNode.class));
    }

    @Test
    void report_existingNode_shouldUpsertNotCreate() {
        WorkerNode existing = new WorkerNode();
        existing.setId(1L);
        existing.setNodeCode("node-1");
        existing.setCreatedAt(LocalDateTime.now().minusDays(1));
        existing.setDeleted(0);
        existing.setVersion(0);

        when(repository.findByNodeCode("node-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(WorkerNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerNode result = fleetService.report("node-1", "host-1-updated", "8C/16G",
                0.6, 0.7, 0.8, 2.5, 3, null, null, 120);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo("ONLINE");
        assertThat(result.getHost()).isEqualTo("host-1-updated");
        assertThat(result.getCapacity()).isEqualTo("8C/16G");
        assertThat(result.getCpu()).isEqualTo(0.6);
        assertThat(result.getRunningTasks()).isEqualTo(3);
        verify(repository).save(any(WorkerNode.class));
    }

    @Test
    void report_incarnationChange_shouldDetectRestart() {
        WorkerNode existing = new WorkerNode();
        existing.setId(1L);
        existing.setNodeCode("node-1");
        existing.setIncarnation(100L);
        existing.setCreatedAt(LocalDateTime.now().minusDays(1));
        existing.setDeleted(0);
        existing.setVersion(0);

        when(repository.findByNodeCode("node-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(WorkerNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        fleetService.report("node-1", "host-1", "4C/8G", 0.3, 0.45, 0.5, 1.2, 0,
                200L, null, 120);

        // incarnation should be updated to 200
        assertThat(existing.getIncarnation()).isEqualTo(200L);
    }

    @Test
    void report_withRunningInstances_shouldRenewLeases() {
        WorkerNode existing = new WorkerNode();
        existing.setId(1L);
        existing.setNodeCode("node-1");
        existing.setIncarnation(100L);
        existing.setCreatedAt(LocalDateTime.now().minusDays(1));
        existing.setDeleted(0);
        existing.setVersion(0);

        when(repository.findByNodeCode("node-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(WorkerNode.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachine.renewLease(any(), any())).thenReturn(true);

        java.util.UUID id1 = java.util.UUID.randomUUID();
        java.util.UUID id2 = java.util.UUID.randomUUID();

        fleetService.report("node-1", "host-1", "4C/8G", 0.3, 0.45, 0.5, 1.2, 2,
                100L, java.util.List.of(id1, id2), 120);

        verify(stateMachine, org.mockito.Mockito.times(2)).renewLease(any(), any());
    }

    // ── 028: 新节点注册默认容量（SlotManager 0 槽回归防护） ──

    @Test
    void report_newNode_fillsDefaultCapacityIfNull() {
        when(repository.findByNodeCode("node-2")).thenReturn(Optional.empty());
        when(repository.save(any(WorkerNode.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkerNode result = fleetService.report("node-2", "host-2", "4C/8G",
                0.3, 0.45, 0.5, 1.2, 0, 100L, null, 120);

        // 028: 新节点 maxConcurrentTasks/reservedTestSlots 为 null 时补默认值，
        // 否则 INSERT NULL → SlotManager 当 0 槽 → distributed worker 永收不到下发
        assertThat(result.getMaxConcurrentTasks()).isEqualTo(10);
        assertThat(result.getReservedTestSlots()).isEqualTo(1);
    }

    @Test
    void report_existingNode_preservesCustomCapacity() {
        WorkerNode existing = new WorkerNode();
        existing.setId(1L);
        existing.setNodeCode("node-3");
        existing.setMaxConcurrentTasks(20);
        existing.setReservedTestSlots(5);
        existing.setStatus("ONLINE");
        existing.setIncarnation(100L);
        existing.setCreatedAt(LocalDateTime.now().minusDays(1));
        existing.setDeleted(0);
        existing.setVersion(0);

        when(repository.findByNodeCode("node-3")).thenReturn(Optional.of(existing));
        when(repository.save(any(WorkerNode.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkerNode result = fleetService.report("node-3", "host-3", "4C/8G",
                0.3, 0.45, 0.5, 1.2, 2, 100L, null, 120);

        // 已有节点保留其自定义容量，不被默认值覆盖
        assertThat(result.getMaxConcurrentTasks()).isEqualTo(20);
        assertThat(result.getReservedTestSlots()).isEqualTo(5);
    }

    @Test
    void markStaleOffline_shouldMarkOnlyStaleNodes() {
        WorkerNode staleNode = new WorkerNode();
        staleNode.setId(1L);
        staleNode.setNodeCode("stale-node");
        staleNode.setStatus("ONLINE");
        staleNode.setLastHeartbeat(LocalDateTime.now().minus(Duration.ofSeconds(60)));

        WorkerNode freshNode = new WorkerNode();
        freshNode.setId(2L);
        freshNode.setNodeCode("fresh-node");
        freshNode.setStatus("ONLINE");
        freshNode.setLastHeartbeat(LocalDateTime.now());

        when(repository.findByStatus("ONLINE")).thenReturn(List.of(staleNode, freshNode));
        when(repository.save(any(WorkerNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int marked = fleetService.markStaleOffline();

        assertThat(marked).isEqualTo(1);
        assertThat(staleNode.getStatus()).isEqualTo("OFFLINE");
        assertThat(freshNode.getStatus()).isEqualTo("ONLINE");
        verify(repository).save(staleNode);
        verify(repository, never()).save(freshNode);
    }
}
