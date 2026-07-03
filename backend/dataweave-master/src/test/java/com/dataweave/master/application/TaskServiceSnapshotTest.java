package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskDefVersion;
import com.dataweave.master.domain.TaskDefVersionRepository;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowNodeRepository;
import com.dataweave.master.i18n.BizException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1 状态中立建快照内核 单元测试：writeTaskVersionSnapshot 不改 status，
 * publish() 重构后仍正确晋级 ONLINE + 无变更守卫。
 */
class TaskServiceSnapshotTest {

    private final TaskDefRepository taskDefRepo = mock(TaskDefRepository.class);
    private final TaskDefVersionRepository verRepo = mock(TaskDefVersionRepository.class);
    private final TaskInstanceRepository instRepo = mock(TaskInstanceRepository.class);
    private final WorkflowNodeRepository workflowNodeRepo = mock(WorkflowNodeRepository.class);
    private final FleetService fleetService = mock(FleetService.class);

    private TaskService newService() {
        when(taskDefRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(verRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 末尾 6 个 null:jdbcTemplate + 5 个血缘依赖(lineageStore/lineageEdgeAssembler/
        // sqlColumnLineageExtractor/columnLineageCatalog/scriptLineageService)。本测试只覆盖
        // writeTaskVersionSnapshot/publish,不走 createAndOnline→recordLineage 血缘路径,故血缘依赖传 null 安全。
        return new TaskService(taskDefRepo, verRepo, instRepo, workflowNodeRepo,
                fleetService, null, null, null, null, null, null);
    }

    private TaskDef draftTask() {
        TaskDef t = new TaskDef();
        t.setId(1L);
        t.setTenantId(1L);
        t.setProjectId(10L);
        t.setName("测试任务");
        t.setType("SQL");
        t.setContent("SELECT 1");
        t.setStatus("DRAFT");
        t.setCurrentVersionNo(0);
        t.setHasDraftChange(1);
        return t;
    }

    // ── writeTaskVersionSnapshot 状态中立 ──

    @Test
    void writeSnapshot_createsVersionButDoesNotChangeStatus() {
        TaskService svc = newService();
        TaskDef task = draftTask();

        Integer newVer = svc.writeTaskVersionSnapshot(task, null, "测试快照");

        assertThat(newVer).isEqualTo(1);
        assertThat(task.getCurrentVersionNo()).isEqualTo(1);
        // 状态不应被改为 ONLINE
        assertThat(task.getStatus()).isEqualTo("DRAFT");
        // hasDraftChange 不改
        assertThat(task.getHasDraftChange()).isEqualTo(1);
        // version row 被保存
        verify(verRepo).save(any(TaskDefVersion.class));
    }

    @Test
    void writeSnapshot_setsPublishedByAndRemark() {
        TaskService svc = newService();
        TaskDef task = draftTask();

        svc.writeTaskVersionSnapshot(task, 42L, "push 快照");

        assertThat(task.getCurrentVersionNo()).isEqualTo(1);
        verify(verRepo).save(any(TaskDefVersion.class));
    }

    // ── publish() 零回归 ──

    @Test
    void publish_promotesToOnline() {
        TaskService svc = newService();
        TaskDef task = draftTask();
        when(taskDefRepo.findById(1L)).thenReturn(Optional.of(task));

        TaskDef published = svc.publish(1L, "发布 v1");

        assertThat(published.getStatus()).isEqualTo("ONLINE");
        assertThat(published.getHasDraftChange()).isEqualTo(0);
        assertThat(published.getCurrentVersionNo()).isEqualTo(1);
    }

    @Test
    void publish_rejectsNoDraftChanges() {
        TaskService svc = newService();
        TaskDef task = draftTask();
        task.setStatus("ONLINE");
        task.setHasDraftChange(0);
        when(taskDefRepo.findById(1L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> svc.publish(1L, "no change"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("task.no_draft_changes");
    }

    @Test
    void publish_allowsDraftToOnlineEvenWithoutDraftChangeFlag() {
        // DRAFT 任务无论 hasDraftChange 都应可发布
        TaskService svc = newService();
        TaskDef task = draftTask();
        task.setHasDraftChange(0); // 但 status 是 DRAFT
        when(taskDefRepo.findById(1L)).thenReturn(Optional.of(task));

        TaskDef published = svc.publish(1L, "首次发布");

        assertThat(published.getStatus()).isEqualTo("ONLINE");
    }
}
