package com.dataweave.master.application;

import com.dataweave.master.application.readiness.ReadinessTestHelper;
import com.dataweave.master.domain.EventBus;
import com.dataweave.master.quality.application.TaskSucceededEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F1 收口回归：casTaskTerminal 的终态副作用（UI 事件 / FAILED alert / SUCCESS 质量门禁）
 * 挪到事务 afterCommit——事务回滚不发假事件；提交才发；无事务立即发（向后兼容）。
 */
@DisplayName("InstanceStateMachine 终态副作用 afterCommit")
class InstanceStateMachineTerminalEventTest {

    private DataSource ds;
    private JdbcTemplate jdbc;
    private EventBus eventBus;
    private ApplicationEventPublisher publisher;
    private InstanceStateMachine sm;

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("test-ism-terminal-" + System.currentTimeMillis())
                .build();
        jdbc = new JdbcTemplate(ds);
        ReadinessTestHelper.createMinimalSchema(jdbc);
        eventBus = mock(EventBus.class);
        publisher = mock(ApplicationEventPublisher.class);
        sm = new InstanceStateMachine(jdbc, eventBus, publisher);
    }

    private UUID seedRunning() {
        UUID id = UUID.randomUUID();
        UUID wi = UUID.randomUUID();
        jdbc.update("INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, " +
                "task_id, state, deleted, unmet_deps) VALUES (?,1,1,?,1,'RUNNING',0,0)", id, wi);
        return id;
    }

    @Test
    @DisplayName("事务回滚 → 不发假事件，状态回退")
    void rollback_noFalseEvents() {
        UUID id = seedRunning();
        TransactionTemplate tt = new TransactionTemplate(new DataSourceTransactionManager(ds));

        assertThatThrownBy(() -> tt.executeWithoutResult(status -> {
            boolean ok = sm.casTaskTerminal(id, "RUNNING", "SUCCESS", null);
            assertThat(ok).as("CAS 事务内成功").isTrue();
            throw new RuntimeException("force rollback（模拟 writeTerminalSignal 失败）");
        })).hasMessageContaining("force rollback");

        // 回滚后：状态回退到 RUNNING，且 SUCCESS 质量事件 / UI 事件均未发出（无假事件）
        String state = jdbc.queryForObject("SELECT state FROM task_instance WHERE id=?", String.class, id);
        assertThat(state).as("回滚后状态回退").isEqualTo("RUNNING");
        verifyNoInteractions(publisher);
        verify(eventBus, never()).publish(startsWith("dw:evt:"), anyString());
    }

    @Test
    @DisplayName("事务提交 → 终态副作用真发出")
    void commit_eventsFire() {
        UUID id = seedRunning();
        TransactionTemplate tt = new TransactionTemplate(new DataSourceTransactionManager(ds));

        tt.executeWithoutResult(status ->
                assertThat(sm.casTaskTerminal(id, "RUNNING", "SUCCESS", null)).isTrue());

        String state = jdbc.queryForObject("SELECT state FROM task_instance WHERE id=?", String.class, id);
        assertThat(state).isEqualTo("SUCCESS");
        verify(publisher).publishEvent(any(TaskSucceededEvent.class));
        verify(eventBus).publish(startsWith("dw:evt:"), anyString());
    }

    @Test
    @DisplayName("无活动事务 → 立即发（向后兼容 auto-commit 调用方）")
    void noTransaction_immediateFire() {
        UUID id = seedRunning();

        assertThat(sm.casTaskTerminal(id, "RUNNING", "SUCCESS", null)).isTrue();

        // 无事务：afterCommit 无处挂 → 立即执行，语义等价旧行为
        verify(publisher).publishEvent(any(TaskSucceededEvent.class));
        verify(eventBus).publish(startsWith("dw:evt:"), anyString());
    }
}
