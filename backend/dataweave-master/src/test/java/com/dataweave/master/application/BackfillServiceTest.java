package com.dataweave.master.application;

import com.dataweave.master.application.OpsContracts.BackfillRequest;
import com.dataweave.master.application.OpsContracts.BackfillRunView;
import com.dataweave.master.domain.BackfillRun;
import com.dataweave.master.domain.BackfillRunRepository;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** data-ops-center Stream A：补数据生成编排 + 区间展开 + 校验（Mockito，无 DB）。 */
class BackfillServiceTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private BackfillRunRepository backfillRunRepository;
    private WorkflowTriggerService triggerService;
    private TaskDefRepository taskDefRepository;
    private WorkflowDefRepository workflowDefRepository;
    private JdbcTemplate jdbc;
    private BackfillService backfill;

    @BeforeEach
    void setUp() {
        backfillRunRepository = mock(BackfillRunRepository.class);
        triggerService = mock(WorkflowTriggerService.class);
        taskDefRepository = mock(TaskDefRepository.class);
        workflowDefRepository = mock(WorkflowDefRepository.class);
        jdbc = mock(JdbcTemplate.class);
        backfill = new BackfillService(backfillRunRepository, triggerService, taskDefRepository,
                workflowDefRepository, jdbc);

        when(backfillRunRepository.save(any())).thenAnswer(inv -> {
            BackfillRun r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(RUN_ID);
            }
            return r;
        });
        // total 回填查询
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(3);
    }

    private void stubTask() {
        TaskDef def = new TaskDef();
        def.setId(10L);
        def.setName("t");
        def.setTenantId(1L);
        def.setProjectId(1L);
        def.setCurrentVersionNo(2);
        when(taskDefRepository.findById(10L)).thenReturn(Optional.of(def));
    }

    @Test
    void taskBackfillGeneratesOnePerDate() {
        stubTask();
        BackfillRequest req = new BackfillRequest("task", 10L, "2026-06-20", "2026-06-22", false, 2);

        BackfillRunView view = backfill.submitBackfill(req);

        // 三天 → 三次单任务补数触发，bizDate 精确注入（防 $bizdate 缺省无日志）
        verify(triggerService).triggerBackfillTaskRun(eq(10L), eq("2026-06-20"), eq(RUN_ID), any());
        verify(triggerService).triggerBackfillTaskRun(eq(10L), eq("2026-06-21"), eq(RUN_ID), any());
        verify(triggerService).triggerBackfillTaskRun(eq(10L), eq("2026-06-22"), eq(RUN_ID), any());
        assertThat(view.id()).isEqualTo(RUN_ID);
        assertThat(view.dateStart()).isEqualTo("2026-06-20");
        assertThat(view.dateEnd()).isEqualTo("2026-06-22");
    }

    @Test
    void singleDayBackfillGeneratesOnce() {
        stubTask();
        BackfillRequest req = new BackfillRequest("task", 10L, "2026-06-20", "2026-06-20", false, 1);

        backfill.submitBackfill(req);

        verify(triggerService, times(1)).triggerBackfillTaskRun(eq(10L), eq("2026-06-20"), eq(RUN_ID), any());
    }

    @Test
    void workflowBackfillTriggersDagPerDate() {
        WorkflowDef wf = new WorkflowDef();
        wf.setId(20L);
        wf.setName("wf");
        wf.setTenantId(1L);
        wf.setProjectId(1L);
        when(workflowDefRepository.findById(20L)).thenReturn(Optional.of(wf));
        BackfillRequest req = new BackfillRequest("workflow", 20L, "2026-06-20", "2026-06-21", true, 1);

        backfill.submitBackfill(req);

        verify(triggerService).trigger(eq(wf), eq("BACKFILL"), eq("2026-06-20"), eq(null), any(),
                eq("FULL"), eq(null), eq("BACKFILL"), eq(RUN_ID));
        verify(triggerService).trigger(eq(wf), eq("BACKFILL"), eq("2026-06-21"), eq(null), any(),
                eq("FULL"), eq(null), eq("BACKFILL"), eq(RUN_ID));
    }

    @Test
    void endBeforeStartRejected() {
        BackfillRequest req = new BackfillRequest("task", 10L, "2026-06-22", "2026-06-20", false, 1);
        assertThatThrownBy(() -> backfill.submitBackfill(req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownTargetTypeRejected() {
        BackfillRequest req = new BackfillRequest("nope", 10L, "2026-06-20", "2026-06-20", false, 1);
        assertThatThrownBy(() -> backfill.submitBackfill(req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingTargetRejected() {
        BackfillRequest req = new BackfillRequest("task", null, "2026-06-20", "2026-06-20", false, 1);
        assertThatThrownBy(() -> backfill.submitBackfill(req)).isInstanceOf(IllegalArgumentException.class);
    }
}
