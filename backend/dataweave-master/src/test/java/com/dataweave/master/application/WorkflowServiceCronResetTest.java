package com.dataweave.master.application;

import com.dataweave.master.domain.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 cron/schedule_type 变更时 next_trigger_time 自动重置为 null，
 * 让调度器 backfill 重新按新表达式计算下次触发时刻。
 */
class WorkflowServiceCronResetTest {

    private final WorkflowDefRepository wfRepo = mock(WorkflowDefRepository.class);
    private final WorkflowDefVersionRepository wfVerRepo = mock(WorkflowDefVersionRepository.class);
    private final WorkflowNodeRepository nodeRepo = mock(WorkflowNodeRepository.class);
    private final WorkflowEdgeRepository edgeRepo = mock(WorkflowEdgeRepository.class);
    private final WorkflowDependencyRepository depRepo = mock(WorkflowDependencyRepository.class);
    private final TaskDefRepository taskDefRepo = mock(TaskDefRepository.class);
    private final TaskDefVersionRepository taskVerRepo = mock(TaskDefVersionRepository.class);
    private final WorkflowGraphValidator graphValidator = mock(WorkflowGraphValidator.class);

    private WorkflowService newService() {
        when(wfRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(wfVerRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(nodeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(edgeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new WorkflowService(wfRepo, wfVerRepo, nodeRepo, edgeRepo, depRepo,
                taskDefRepo, taskVerRepo, graphValidator, null,
                new tools.jackson.databind.ObjectMapper());
    }

    private WorkflowDef onlineCronWf() {
        WorkflowDef wf = new WorkflowDef();
        wf.setId(1L);
        wf.setTenantId(1L);
        wf.setProjectId(10L);
        wf.setName("测试周期流");
        wf.setStatus("ONLINE");
        wf.setScheduleType("CRON");
        wf.setCron("0 0 20 * * ?");
        wf.setNextTriggerTime(LocalDateTime.of(2026, 7, 9, 20, 0, 0));  // 旧 cron 的 next
        wf.setHasDraftChange(0);
        return wf;
    }

    // ── update() 触发 applyConfigPatch ──

    @Test
    void update_changedCron_resetsNextTriggerTime() {
        WorkflowService svc = newService();
        WorkflowDef wf = onlineCronWf();
        when(wfRepo.findById(1L)).thenReturn(Optional.of(wf));

        WorkflowDef patch = new WorkflowDef();
        patch.setCron("0 * * * * ?");  // 改为每分钟
        svc.update(1L, patch);

        // next_trigger_time 应被重置为 null，调度器下次扫描会重新计算
        assertThat(wf.getNextTriggerTime()).isNull();
        assertThat(wf.getCron()).isEqualTo("0 * * * * ?");
    }

    @Test
    void update_changedScheduleType_resetsNextTriggerTime() {
        WorkflowService svc = newService();
        WorkflowDef wf = onlineCronWf();
        when(wfRepo.findById(1L)).thenReturn(Optional.of(wf));

        WorkflowDef patch = new WorkflowDef();
        patch.setScheduleType("FIXED_RATE");
        svc.update(1L, patch);

        assertThat(wf.getNextTriggerTime()).isNull();
    }

    @Test
    void update_sameCron_preservesNextTriggerTime() {
        WorkflowService svc = newService();
        WorkflowDef wf = onlineCronWf();
        LocalDateTime existingNext = wf.getNextTriggerTime();
        when(wfRepo.findById(1L)).thenReturn(Optional.of(wf));

        WorkflowDef patch = new WorkflowDef();
        patch.setCron("0 0 20 * * ?");  // 未变化
        patch.setName("新名称");  // 只改名称
        svc.update(1L, patch);

        // cron 未变，next_trigger_time 应保留
        assertThat(wf.getNextTriggerTime()).isEqualTo(existingNext);
        assertThat(wf.getName()).isEqualTo("新名称");
    }

    @Test
    void update_changedCronAndName_resetsNextTriggerTime() {
        WorkflowService svc = newService();
        WorkflowDef wf = onlineCronWf();
        when(wfRepo.findById(1L)).thenReturn(Optional.of(wf));

        WorkflowDef patch = new WorkflowDef();
        patch.setCron("*/30 * * * * *");  // 改为每30秒
        patch.setName("改名为每30秒");
        svc.update(1L, patch);

        assertThat(wf.getNextTriggerTime()).isNull();
        assertThat(wf.getName()).isEqualTo("改名为每30秒");
        assertThat(wf.getCron()).isEqualTo("*/30 * * * * *");
    }

    @Test
    void update_onlyNameChange_preservesNextTriggerTime() {
        WorkflowService svc = newService();
        WorkflowDef wf = onlineCronWf();
        LocalDateTime existingNext = wf.getNextTriggerTime();
        when(wfRepo.findById(1L)).thenReturn(Optional.of(wf));

        WorkflowDef patch = new WorkflowDef();
        patch.setName("改名而已");
        svc.update(1L, patch);

        assertThat(wf.getNextTriggerTime()).isEqualTo(existingNext);
    }
}
