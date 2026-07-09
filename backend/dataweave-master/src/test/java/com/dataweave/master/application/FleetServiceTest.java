package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FleetService test（028 注册默认容量 + 060 节点级回收/恢复唤醒/健康列保护）。
 */
@ExtendWith(MockitoExtension.class)
class FleetServiceTest {

    @Mock private WorkerNodeRepository repository;
    @Mock private InstanceStateMachine stateMachine;
    @Mock private JdbcTemplate jdbc;
    @Mock private EventBus eventBus;
    @Mock private ApplicationEventPublisher eventPublisher;

    private static final long STABILIZATION_MS = 15000L;
    private static final int INFRA_MAX = 10;

    private FleetService fleetService;

    @BeforeEach
    void setUp() {
        fleetService = new FleetService(repository, stateMachine, jdbc, eventBus, STABILIZATION_MS, INFRA_MAX);
    }

    @Test
    void report_newNode_shouldSaveAsOnline_andEnterStabilizationWindow() {
        when(repository.findByNodeCode("node-1")).thenReturn(Optional.empty());
        when(repository.save(any(WorkerNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerNode result = fleetService.report("node-1", "host-1", "4C/8G",
                0.3, 0.45, 0.5, 1.2, 0, null, null, 120);

        assertThat(result.getStatus()).isEqualTo("ONLINE");
        assertThat(result.getHost()).isEqualTo("host-1");
        assertThat(result.getLastHeartbeat()).isNotNull();
        assertThat(result.getIncarnationSince()).isNotNull();  // 060：新节点落 incarnation_since（进稳定窗）
        verify(repository).save(any(WorkerNode.class));
    }

    @Test
    void report_existingNode_usesTargetedUpdate_notFullSave() {
        // 060：既有节点心跳走 targeted UPDATE（保护并发写的健康列），不再 repository.save 全字段覆写
        WorkerNode existing = node("node-1", 1L);
        existing.setIncarnation(100L);
        when(repository.findByNodeCode("node-1")).thenReturn(Optional.of(existing));

        WorkerNode result = fleetService.report("node-1", "host-1-updated", "8C/16G",
                0.6, 0.7, 0.8, 2.5, 3, 100L, null, 120);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo("ONLINE");
        assertThat(result.getHost()).isEqualTo("host-1-updated");
        // 060：既有节点心跳走 targeted UPDATE，不经 repository.save 全字段覆写（保护并发写的健康列）
        verify(repository, never()).save(any(WorkerNode.class));
    }

    @Test
    void report_incarnationChange_nodeLevelReclaim() {
        // 060（I1）：incarnation 变化 → 节点级即时回收该节点全部活跃实例为 infra（不烧业务）
        WorkerNode existing = node("node-1", 1L);
        existing.setIncarnation(100L);
        when(repository.findByNodeCode("node-1")).thenReturn(Optional.of(existing));
        UUID activeInstance = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(UUID.class), any())).thenReturn(List.of(activeInstance));

        fleetService.report("node-1", "host-1", "4C/8G", 0.3, 0.45, 0.5, 1.2, 0, 200L, null, 120);

        assertThat(existing.getIncarnation()).isEqualTo(200L);
        assertThat(existing.getIncarnationSince()).isNotNull();  // 纪元变化 → 重置稳定窗
        verify(stateMachine).reclaimInfra(eq(activeInstance), eq(INFRA_MAX));  // 节点级回收（infra）
        verify(eventBus).publish(eq(InstanceStates.WAKE_CHANNEL), eq("worker-restart"));
    }

    @Test
    void report_recoveryFromOffline_wakesDrain() {
        // 060（FR-014）：节点 OFFLINE（心跳陈旧）→ report 恢复 ONLINE → 不可用→可用 → 发 WAKE 抽干等待
        WorkerNode existing = node("node-1", 1L);
        existing.setStatus("OFFLINE");
        existing.setLastHeartbeat(LocalDateTime.now().minus(Duration.ofSeconds(60)));  // 陈旧 → wasAvailable=false
        existing.setIncarnation(100L);
        existing.setIncarnationSince(LocalDateTime.now().minus(Duration.ofSeconds(60)));  // 已过稳定窗
        when(repository.findByNodeCode("node-1")).thenReturn(Optional.of(existing));

        fleetService.report("node-1", "host-1", "4C/8G", 0.3, 0.45, 0.5, 1.2, 0, 100L, null, 120);

        verify(eventBus).publish(eq(InstanceStates.WAKE_CHANNEL), eq("node-recovered"));
    }

    @Test
    void report_healthyHeartbeat_noSpuriousWake() {
        // 稳态心跳（可用→可用）不应发恢复唤醒
        WorkerNode existing = node("node-1", 1L);
        existing.setIncarnation(100L);
        existing.setLastHeartbeat(LocalDateTime.now());  // 新鲜
        existing.setIncarnationSince(LocalDateTime.now().minus(Duration.ofSeconds(60)));  // 已过稳定窗
        when(repository.findByNodeCode("node-1")).thenReturn(Optional.of(existing));

        fleetService.report("node-1", "host-1", "4C/8G", 0.3, 0.45, 0.5, 1.2, 0, 100L, null, 120);

        verify(eventBus, never()).publish(eq(InstanceStates.WAKE_CHANNEL), eq("node-recovered"));
    }

    @Test
    void report_withRunningInstances_shouldRenewLeases() {
        WorkerNode existing = node("node-1", 1L);
        existing.setIncarnation(100L);
        when(repository.findByNodeCode("node-1")).thenReturn(Optional.of(existing));
        when(stateMachine.renewLease(any(), any())).thenReturn(true);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        fleetService.report("node-1", "host-1", "4C/8G", 0.3, 0.45, 0.5, 1.2, 2,
                100L, List.of(id1, id2), 120);

        verify(stateMachine, org.mockito.Mockito.times(2)).renewLease(any(), any());
    }

    @Test
    void report_newNode_fillsDefaultCapacityIfNull() {
        when(repository.findByNodeCode("node-2")).thenReturn(Optional.empty());
        when(repository.save(any(WorkerNode.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkerNode result = fleetService.report("node-2", "host-2", "4C/8G",
                0.3, 0.45, 0.5, 1.2, 0, 100L, null, 120);

        assertThat(result.getMaxConcurrentTasks()).isEqualTo(100);
        assertThat(result.getReservedTestSlots()).isEqualTo(1);
    }

    @Test
    void markStaleOffline_usesTargetedUpdate() {
        // 060：markStaleOffline 走 targeted UPDATE（仅 status），不 clobber 健康列
        WorkerNode stale = node("stale-node", 1L);
        stale.setStatus("ONLINE");
        stale.setLastHeartbeat(LocalDateTime.now().minus(Duration.ofSeconds(60)));
        WorkerNode fresh = node("fresh-node", 2L);
        fresh.setStatus("ONLINE");
        fresh.setLastHeartbeat(LocalDateTime.now());
        when(repository.findByStatus("ONLINE")).thenReturn(List.of(stale, fresh));

        int marked = fleetService.markStaleOffline();

        assertThat(marked).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo("OFFLINE");   // 060：targeted UPDATE（仅 status），逻辑生效
        assertThat(fresh.getStatus()).isEqualTo("ONLINE");
    }

    private static WorkerNode node(String code, long id) {
        WorkerNode n = new WorkerNode();
        n.setId(id);
        n.setNodeCode(code);
        n.setStatus("ONLINE");
        n.setDeleted(0);
        n.setVersion(0);
        n.setCreatedAt(LocalDateTime.now().minusDays(1));
        return n;
    }
}
