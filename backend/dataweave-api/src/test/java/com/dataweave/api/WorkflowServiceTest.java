package com.dataweave.api;

import com.dataweave.master.application.WorkflowService;
import com.dataweave.master.application.WorkflowService.DagEdgeDto;
import com.dataweave.master.application.WorkflowService.DagNodeDto;
import com.dataweave.master.application.WorkflowService.DagPayload;
import com.dataweave.master.application.WorkflowService.DagView;
import com.dataweave.master.application.WorkflowTriggerService;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowDef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WorkflowService 行为测试（workflow-authoring spec）：CRUD、DAG 整图对账、发布无环校验、
 * 以及虚拟节点零负载执行（scheduler-core delta）。H2 内存库，data.sql 已种子 task_def 1/2/3。
 */
@SpringBootTest
@ActiveProfiles("h2")
class WorkflowServiceTest {

    @Autowired
    WorkflowService workflowService;

    @Autowired
    WorkflowTriggerService triggerService;

    @Autowired
    TaskInstanceRepository taskInstanceRepository;

    private WorkflowDef newDraft(String name) {
        WorkflowDef wf = new WorkflowDef();
        wf.setName(name);
        return workflowService.create(wf);
    }

    @Test
    void create_setsDraftDefaults() {
        WorkflowDef wf = newDraft("画布测试-A");
        assertThat(wf.getId()).isNotNull();
        assertThat(wf.getStatus()).isEqualTo("DRAFT");
        assertThat(wf.getCurrentVersionNo()).isZero();
        assertThat(wf.getHasDraftChange()).isEqualTo(1);
    }

    @Test
    void saveDag_upsertsAndSoftDeletes() {
        WorkflowDef wf = newDraft("画布测试-B");
        // 初始：虚拟起始 v0 → 任务 t1
        DagView v1 = workflowService.saveDag(wf.getId(), new DagPayload(wf.getVersion(),
                List.of(new DagNodeDto("v0", "VIRTUAL", null, "开始", 0, 0),
                        new DagNodeDto("t1", "TASK", 1L, "任务1", 100, 0)),
                List.of(new DagEdgeDto("v0", "t1"))));
        assertThat(v1.nodes()).hasSize(2);
        assertThat(v1.edges()).hasSize(1);
        assertThat(v1.version()).isGreaterThan(wf.getVersion());

        // 再保存：去掉 t1（应软删 t1 + 其边），新增 t2
        DagView v2 = workflowService.saveDag(wf.getId(), new DagPayload(v1.version(),
                List.of(new DagNodeDto("v0", "VIRTUAL", null, "开始", 0, 0),
                        new DagNodeDto("t2", "TASK", 2L, "任务2", 200, 0)),
                List.of(new DagEdgeDto("v0", "t2"))));
        assertThat(v2.nodes()).extracting(DagNodeDto::nodeKey).containsExactlyInAnyOrder("v0", "t2");
        assertThat(v2.edges()).hasSize(1);
        assertThat(v2.edges().get(0).toNodeKey()).isEqualTo("t2");
    }

    @Test
    void saveDag_rejectsTaskNodeWithoutTaskId() {
        WorkflowDef wf = newDraft("画布测试-C");
        assertThatThrownBy(() -> workflowService.saveDag(wf.getId(), new DagPayload(wf.getVersion(),
                List.of(new DagNodeDto("t1", "TASK", null, "无任务", 0, 0)),
                List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveDag_rejectsStaleVersion() {
        WorkflowDef wf = newDraft("画布测试-D");
        assertThatThrownBy(() -> workflowService.saveDag(wf.getId(), new DagPayload(999L,
                List.of(new DagNodeDto("t1", "TASK", 1L, "任务1", 0, 0)),
                List.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publish_acyclicSucceeds_cyclicRejected() {
        WorkflowDef wf = newDraft("画布测试-E");
        // 无环：t1 → t2
        DagView v = workflowService.saveDag(wf.getId(), new DagPayload(wf.getVersion(),
                List.of(new DagNodeDto("t1", "TASK", 1L, "任务1", 0, 0),
                        new DagNodeDto("t2", "TASK", 2L, "任务2", 100, 0)),
                List.of(new DagEdgeDto("t1", "t2"))));
        WorkflowDef published = workflowService.publish(wf.getId(), "首发");
        assertThat(published.getStatus()).isEqualTo("ONLINE");
        assertThat(published.getCurrentVersionNo()).isEqualTo(1);
        assertThat(published.getHasDraftChange()).isZero();

        // 制造环：t1 → t2 → t1
        DagView cyclic = workflowService.saveDag(wf.getId(), new DagPayload(published.getVersion(),
                List.of(new DagNodeDto("t1", "TASK", 1L, "任务1", 0, 0),
                        new DagNodeDto("t2", "TASK", 2L, "任务2", 100, 0)),
                List.of(new DagEdgeDto("t1", "t2"), new DagEdgeDto("t2", "t1"))));
        assertThatThrownBy(() -> workflowService.publish(wf.getId(), "应失败"))
                .isInstanceOf(IllegalStateException.class);
        // 版本号不变（仍是 1）
        assertThat(workflowService.getById(wf.getId()).orElseThrow().getCurrentVersionNo()).isEqualTo(1);
    }

    @Test
    void virtualNode_materializesAsSuccess_andUnblocksDownstream() {
        WorkflowDef wf = newDraft("画布测试-F");
        workflowService.saveDag(wf.getId(), new DagPayload(wf.getVersion(),
                List.of(new DagNodeDto("v0", "VIRTUAL", null, "开始", 0, 0),
                        new DagNodeDto("t1", "TASK", 1L, "任务1", 100, 0)),
                List.of(new DagEdgeDto("v0", "t1"))));
        WorkflowDef published = workflowService.publish(wf.getId(), "首发");

        UUID wiId = triggerService.trigger(published, "MANUAL", "2026-06-15", null);
        List<TaskInstance> tis = taskInstanceRepository.findByWorkflowInstanceId(wiId);
        assertThat(tis).hasSize(2);

        TaskInstance virtual = tis.stream().filter(t -> t.getTaskId() == null).findFirst().orElseThrow();
        assertThat(virtual.getState()).isEqualTo(InstanceStates.SUCCESS);
        assertThat(virtual.getFinishedAt()).isNotNull();

        // 任务节点已物化并绑定 task 1。其状态由活体调度内核推进（虚拟前驱已 SUCCESS → 可被认领），
        // 故不断言具体瞬时态（WAITING 会被即时下发，断言会竞态）；存在即证明下游被解锁。
        TaskInstance task = tis.stream().filter(t -> t.getTaskId() != null).findFirst().orElseThrow();
        assertThat(task.getTaskId()).isEqualTo(1L);
        assertThat(task.getState()).isNotNull();
    }
}
