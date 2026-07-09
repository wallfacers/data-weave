package com.dataweave.master.application;

import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import com.dataweave.master.infrastructure.InMemoryEventBus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 060（T021）节点恢复唤醒集成测试（FR-014）：节点由不可用（OFFLINE/心跳陈旧）→ 可用时，
 * FleetService 发 WAKE_CHANNEL 消息并由 EventBus 实际送达订阅者（SchedulerKernel 订阅此频道触发认领）。
 *
 * <p>用真 {@link InMemoryEventBus}（同步派发）验证 FleetService→EventBus→订阅者 接缝，
 * 比 FleetServiceTest 的 mock-eventBus 用例更接近端到端（消息真送达）。
 */
class NodeRecoveryWakeTest {

    @Test
    void nodeRecoversFromOffline_wakeDeliveredToSubscriber() {
        InMemoryEventBus bus = new InMemoryEventBus();
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        bus.subscribe(InstanceStates.WAKE_CHANNEL, received::add);

        // 一个 OFFLINE + 心跳陈旧的节点（wasAvailable=false），纪元已过稳定窗
        WorkerNode offline = new WorkerNode();
        offline.setId(1L);
        offline.setNodeCode("node-1");
        offline.setStatus("OFFLINE");
        offline.setIncarnation(100L);
        offline.setLastHeartbeat(LocalDateTime.now().minus(Duration.ofSeconds(60)));
        offline.setIncarnationSince(LocalDateTime.now().minus(Duration.ofSeconds(60)));
        offline.setDeleted(0);
        offline.setVersion(0);

        WorkerNodeRepository repo = mock(WorkerNodeRepository.class);
        when(repo.findByNodeCode("node-1")).thenReturn(Optional.of(offline));

        FleetService fleet = new FleetService(repo, mock(InstanceStateMachine.class),
                mock(JdbcTemplate.class), bus, 15000L, 10);

        // 心跳恢复：刷新状态 ONLINE + 新鲜心跳（incarnation 不变）→ 不可用→可用 → 发 WAKE
        fleet.report("node-1", "host-1", "4C/8G", 0.3, 0.45, 0.5, 1.2, 0, 100L, null, 120);

        assertThat(received).contains("node-recovered");  // WAKE 真送达订阅者（SchedulerKernel 据此抽干等待）
    }

    @Test
    void healthyStableHeartbeat_noWakeDelivered() {
        InMemoryEventBus bus = new InMemoryEventBus();
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        bus.subscribe(InstanceStates.WAKE_CHANNEL, received::add);

        WorkerNode healthy = new WorkerNode();
        healthy.setId(1L);
        healthy.setNodeCode("node-1");
        healthy.setStatus("ONLINE");
        healthy.setIncarnation(100L);
        healthy.setLastHeartbeat(LocalDateTime.now());  // 已新鲜
        healthy.setIncarnationSince(LocalDateTime.now().minus(Duration.ofSeconds(60)));
        healthy.setDeleted(0);
        healthy.setVersion(0);

        WorkerNodeRepository repo = mock(WorkerNodeRepository.class);
        when(repo.findByNodeCode("node-1")).thenReturn(Optional.of(healthy));

        FleetService fleet = new FleetService(repo, mock(InstanceStateMachine.class),
                mock(JdbcTemplate.class), bus, 15000L, 10);

        fleet.report("node-1", "host-1", "4C/8G", 0.3, 0.45, 0.5, 1.2, 0, 100L, null, 120);

        assertThat(received).doesNotContain("node-recovered");  // 稳态可用→可用，不发恢复唤醒
    }
}
