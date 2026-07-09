package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import com.dataweave.master.domain.signal.AlertSignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 060（T020）StuckInstanceSweeper test：无节点等待告警（不判死）+ SUSPENDED 巡检 + 兜底唤醒（FR-012/014/015）。
 */
class StuckInstanceSweeperTest {

    private static final long STUCK_ALERT_MS = 60_000L;  // 60s 阈值（测试用）

    private DataSource ds;
    private JdbcTemplate jdbc;
    private WorkerNodeRepository nodeRepository;
    private EventBus eventBus;
    private ApplicationEventPublisher eventPublisher;
    private StuckInstanceSweeper sweeper;

    @BeforeEach
    void setUp() {
        ds = new DriverManagerDataSource("jdbc:h2:mem:stuck_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        try { jdbc.execute("DROP ALL OBJECTS"); } catch (Exception ignored) {}
        jdbc.execute("CREATE TABLE task_instance (id UUID PRIMARY KEY, state VARCHAR(32), tenant_id BIGINT, "
                + "updated_at TIMESTAMP, deleted SMALLINT DEFAULT 0)");
        nodeRepository = mock(WorkerNodeRepository.class);
        eventBus = mock(EventBus.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        sweeper = new StuckInstanceSweeper(jdbc, nodeRepository, eventBus, eventPublisher, 15000L, STUCK_ALERT_MS);
        when(nodeRepository.findAll()).thenReturn(List.of());  // 默认无可用节点
    }

    @AfterEach
    void tearDown() {
        if (ds instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    private void seed(String state, boolean oldUpdatedAt) {
        LocalDateTime updated = oldUpdatedAt
                ? LocalDateTime.now().minusSeconds(120)  // 超 60s 阈值
                : LocalDateTime.now();
        jdbc.update("INSERT INTO task_instance (id, state, tenant_id, updated_at, deleted) VALUES (?,?,?,?,0)",
                java.util.UUID.randomUUID(), state, 1L, updated);
    }

    private String firstState() {
        return jdbc.queryForObject("SELECT state FROM task_instance LIMIT 1", String.class);
    }

    @Test
    void noNodeWaitingOverThreshold_alertsNotFailed() {
        seed(InstanceStates.WAITING, true);  // 就绪等待，滞留超阈值，无可用节点

        sweeper.sweep();

        // NODE_STARVATION 告警发出（仅可见性，不判死 FR-015）
        ArgumentCaptor<AlertSignal> cap = ArgumentCaptor.forClass(AlertSignal.class);
        verify(eventPublisher).publishEvent(cap.capture());
        assertThat(cap.getValue().getType()).isEqualTo(AlertSignal.Type.NODE_STARVATION);
        // 实例仍 WAITING（未被自动判终态）
        assertThat(firstState()).isEqualTo(InstanceStates.WAITING);
    }

    @Test
    void noNodeWaitingUnderThreshold_noAlert() {
        seed(InstanceStates.WAITING, false);  // 刚入队，未超阈值

        sweeper.sweep();

        verify(eventPublisher, never()).publishEvent(any(AlertSignal.class));
    }

    @Test
    void suspended_patrolAlert() {
        seed(InstanceStates.SUSPENDED, true);

        sweeper.sweep();

        ArgumentCaptor<AlertSignal> cap = ArgumentCaptor.forClass(AlertSignal.class);
        verify(eventPublisher).publishEvent(cap.capture());
        assertThat(cap.getValue().getType()).isEqualTo(AlertSignal.Type.TASK_SUSPENDED);  // FR-012 巡检告警
    }

    @Test
    void waitingAndAvailableNode_wakesDrain() {
        seed(InstanceStates.WAITING, false);
        // 一个可用节点（心跳新鲜 + 纪元过稳定窗 + 未隔离）
        WorkerNode available = new WorkerNode();
        available.setNodeCode("node-1");
        available.setStatus("ONLINE");
        available.setLastHeartbeat(LocalDateTime.now());
        available.setIncarnationSince(LocalDateTime.now().minusSeconds(60));
        when(nodeRepository.findAll()).thenReturn(List.of(available));

        sweeper.sweep();

        verify(eventBus).publish(eq(InstanceStates.WAKE_CHANNEL), eq("stuck-sweep"));  // FR-014 兜底抽干
    }

    @Test
    void noWaiting_noWake() {
        when(nodeRepository.findAll()).thenReturn(List.of());
        sweeper.sweep();
        verify(eventBus, never()).publish(eq(InstanceStates.WAKE_CHANNEL), any());
    }
}
