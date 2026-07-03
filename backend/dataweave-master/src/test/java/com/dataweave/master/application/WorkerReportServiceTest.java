package com.dataweave.master.application;

import com.dataweave.master.application.lineage.LineageEdgeAssembler;
import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.lineage.LineageStore;
import com.dataweave.master.domain.lineage.StatementMetric;
import com.dataweave.master.domain.lineage.TableRef;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature 025 T009：{@link WorkerReportService#reportFinished} 逐 statement 解析写表 → recordSynced
 * 的编排逻辑（mock LineageStore，真 {@link SqlTableExtractor}/{@link LineageEdgeAssembler}）。
 *
 * <p>覆盖：成功→按写表 recordSynced；SELECT(updateCount<0) 跳过；空 metrics（旧 worker）跳过；
 * CAS 竞态让步→不写。降级零阻断（neo4j 异常）由 {@code RecordSyncedNeo4jIT} + reportFinished 外层 try-catch 保证。
 */
class WorkerReportServiceTest {

    private final InstanceStateMachine stateMachine = mock(InstanceStateMachine.class);
    private final TaskInstanceRepository taskInstanceRepository = mock(TaskInstanceRepository.class);

    private WorkerReportService newService(LineageStore lineageStore,
                                           SqlTableExtractor extractor,
                                           LineageEdgeAssembler assembler) {
        return new WorkerReportService(stateMachine, taskInstanceRepository,
                mock(WorkflowStateService.class), mock(RetryService.class),
                mock(SchedulerMetrics.class), mock(SlaService.class),
                mock(EventBus.class), mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(JdbcTemplate.class),
                lineageStore, extractor, assembler);
    }

    private TaskInstance successInstance(UUID id) {
        TaskInstance ti = new TaskInstance();
        ti.setId(id);
        ti.setTenantId(1L);
        ti.setProjectId(1L);
        ti.setTaskId(null);          // null → resolveWriteCoord 走租户级降级 coord（不查 jdbc）
        ti.setBizDate(LocalDate.now().toString());
        ti.setState(InstanceStates.RUNNING);
        return ti;
    }

    @Test
    void reportFinished_success_recordsSyncedRows_perWriteTable() {
        UUID id = UUID.randomUUID();
        when(taskInstanceRepository.findById(id)).thenReturn(Optional.of(successInstance(id)));
        when(stateMachine.casTaskTerminal(eq(id), any(), any(), any())).thenReturn(true);

        LineageStore lineageStore = mock(LineageStore.class);
        WorkerReportService svc = newService(lineageStore,
                new SqlTableExtractor(),
                new LineageEdgeAssembler(new SqlTableExtractor(), mock(JdbcTemplate.class)));

        // INSERT 写 orders_clean(updateCount=5) 收集；SELECT(updateCount<0) 跳过
        List<StatementMetric> metrics = List.of(
                new StatementMetric("INSERT INTO orders_clean(id) VALUES (1)", 5),
                new StatementMetric("SELECT 1", -1));
        svc.reportFinished(id, 0, "ok", metrics);

        // 仅 INSERT 触发一次 recordSynced（rowCount=5，bytes/taskDefId=null）；SELECT 跳过
        verify(lineageStore, times(1)).recordSynced(eq(1L), eq(1L), eq(String.valueOf(id)),
                any(TableRef.class), eq(5L), isNull(), eq(LocalDate.now().toString()), isNull());
    }

    @Test
    void reportFinished_multiTable_eachRecordSynced() {
        UUID id = UUID.randomUUID();
        when(taskInstanceRepository.findById(id)).thenReturn(Optional.of(successInstance(id)));
        when(stateMachine.casTaskTerminal(any(), any(), any(), any())).thenReturn(true);

        LineageStore lineageStore = mock(LineageStore.class);
        WorkerReportService svc = newService(lineageStore,
                new SqlTableExtractor(),
                new LineageEdgeAssembler(new SqlTableExtractor(), mock(JdbcTemplate.class)));

        // 多 statement 写两表（US3：每表各 recordSynced，共享/各持 updateCount）
        List<StatementMetric> metrics = List.of(
                new StatementMetric("INSERT INTO orders_clean(id) VALUES (1)", 100),
                new StatementMetric("INSERT INTO orders_dwd(id) VALUES (1)", 50));
        svc.reportFinished(id, 0, "ok", metrics);

        verify(lineageStore, times(2)).recordSynced(anyLong(), anyLong(), any(), any(TableRef.class),
                anyLong(), any(), any(), any());
    }

    @Test
    void reportFinished_emptyMetrics_skipsRecordSynced() {
        UUID id = UUID.randomUUID();
        when(taskInstanceRepository.findById(id)).thenReturn(Optional.of(successInstance(id)));
        when(stateMachine.casTaskTerminal(any(), any(), any(), any())).thenReturn(true);

        LineageStore lineageStore = mock(LineageStore.class);
        WorkerReportService svc = newService(lineageStore,
                new SqlTableExtractor(),
                new LineageEdgeAssembler(new SqlTableExtractor(), mock(JdbcTemplate.class)));

        svc.reportFinished(id, 0, "ok", List.of());   // 旧 worker：空 metrics

        verify(lineageStore, never()).recordSynced(anyLong(), anyLong(), any(), any(),
                anyLong(), any(), any(), any());
    }

    @Test
    void reportFinished_casRace_skipsRecordSynced() {
        UUID id = UUID.randomUUID();
        when(taskInstanceRepository.findById(id)).thenReturn(Optional.of(successInstance(id)));
        when(stateMachine.casTaskTerminal(any(), any(), any(), any())).thenReturn(false);   // 竞态让步

        LineageStore lineageStore = mock(LineageStore.class);
        WorkerReportService svc = newService(lineageStore,
                new SqlTableExtractor(),
                new LineageEdgeAssembler(new SqlTableExtractor(), mock(JdbcTemplate.class)));

        svc.reportFinished(id, 0, "ok", List.of(new StatementMetric("INSERT INTO t VALUES (1)", 1)));

        verify(lineageStore, never()).recordSynced(anyLong(), anyLong(), any(), any(),
                anyLong(), any(), any(), any());
    }
}
