package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流状态聚合矩阵单测（design-data-model.md §4）。聚合是纯函数，免 Spring 上下文。
 */
class WorkflowStateServiceTest {

    private final WorkflowStateService service = new WorkflowStateService(null, null, null);

    private static TaskInstance node(String state, String runMode) {
        TaskInstance t = new TaskInstance();
        t.setState(state);
        t.setRunMode(runMode);
        return t;
    }

    private static TaskInstance n(String state) {
        return node(state, "NORMAL");
    }

    @Test
    void 全部未运行_聚合为_NOT_RUN() {
        assertThat(service.aggregate(List.of(n("NOT_RUN"), n("NOT_RUN")))).isEqualTo("NOT_RUN");
    }

    @Test
    void 已触发未跑首节点_聚合为_WAITING() {
        assertThat(service.aggregate(List.of(n("WAITING"), n("WAITING")))).isEqualTo("WAITING");
    }

    @Test
    void 部分成功且仍有待跑_聚合为_RUNNING_推进中() {
        assertThat(service.aggregate(List.of(n("SUCCESS"), n("WAITING")))).isEqualTo("RUNNING");
        assertThat(service.aggregate(List.of(n("SUCCESS"), n("NOT_RUN")))).isEqualTo("RUNNING");
    }

    @Test
    void 有节点在跑_聚合为_RUNNING() {
        assertThat(service.aggregate(List.of(n("RUNNING"), n("WAITING")))).isEqualTo("RUNNING");
        // 有失败但仍有在跑 → 仍 RUNNING（尚能推进）
        assertThat(service.aggregate(List.of(n("RUNNING"), n("FAILED")))).isEqualTo("RUNNING");
    }

    @Test
    void 有失败且无在跑_聚合为_FAILED() {
        assertThat(service.aggregate(List.of(n("FAILED"), n("SUCCESS")))).isEqualTo("FAILED");
        assertThat(service.aggregate(List.of(n("FAILED"), n("STOPPED")))).isEqualTo("FAILED");
    }

    @Test
    void 全部成功_聚合为_SUCCESS() {
        assertThat(service.aggregate(List.of(n("SUCCESS"), n("SUCCESS")))).isEqualTo("SUCCESS");
    }

    @Test
    void 全部停止_聚合为_STOPPED() {
        assertThat(service.aggregate(List.of(n("STOPPED"), n("STOPPED")))).isEqualTo("STOPPED");
    }

    @Test
    void TEST_试跑节点不参与聚合() {
        // 仅有的成功节点是 TEST，正式节点全 NOT_RUN → 聚合应为 NOT_RUN（TEST 被忽略）
        assertThat(service.aggregate(List.of(node("SUCCESS", "TEST"), n("NOT_RUN")))).isEqualTo("NOT_RUN");
        // 全是 TEST → 无正式节点 → NOT_RUN
        assertThat(service.aggregate(List.of(node("RUNNING", "TEST")))).isEqualTo("NOT_RUN");
    }

    @Test
    void 空集合_聚合为_NOT_RUN() {
        assertThat(service.aggregate(List.of())).isEqualTo("NOT_RUN");
    }

    @Test
    void 已认领待跑或软抢占回炉中_视为进行中_聚合为_RUNNING() {
        // 回归：DISPATCHED/PREEMPTED 曾误落入 default→NOT_RUN 分支，被当作「未开始」。
        assertThat(service.aggregate(List.of(n("DISPATCHED"), n("WAITING")))).isEqualTo("RUNNING");
        assertThat(service.aggregate(List.of(n("PREEMPTED"), n("WAITING")))).isEqualTo("RUNNING");
    }

    // ── computeAndUpdate：除聚合态外，须维护 completed_tasks / failed_tasks 进度计数 ──

    @Test
    void computeAndUpdate_全部成功_completed等于节点数() {
        UUID wid = UUID.randomUUID();
        WorkflowInstance wi = new WorkflowInstance();
        wi.setId(wid);
        wi.setState("RUNNING");
        wi.setCompletedTasks(1);  // 触发时初值（如含一个物化即成功的虚拟节点）
        wi.setFailedTasks(0);
        wi.setTotalTasks(5);

        TaskInstanceRepository tiRepo = mock(TaskInstanceRepository.class);
        WorkflowInstanceRepository wiRepo = mock(WorkflowInstanceRepository.class);
        when(wiRepo.findById(wid)).thenReturn(Optional.of(wi));
        when(tiRepo.findByWorkflowInstanceId(wid))
                .thenReturn(List.of(n("SUCCESS"), n("SUCCESS"), n("SUCCESS"), n("SUCCESS"), n("SUCCESS")));
        when(wiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String state = new WorkflowStateService(tiRepo, wiRepo, mock(EventBus.class)).computeAndUpdate(wid).orElseThrow();

        assertThat(state).isEqualTo("SUCCESS");
        assertThat(wi.getCompletedTasks()).isEqualTo(5);
        assertThat(wi.getFailedTasks()).isEqualTo(0);
    }

    @Test
    void computeAndUpdate_含失败_分别计入_completed与failed() {
        UUID wid = UUID.randomUUID();
        WorkflowInstance wi = new WorkflowInstance();
        wi.setId(wid);
        wi.setState("RUNNING");
        wi.setCompletedTasks(1);
        wi.setFailedTasks(0);
        wi.setTotalTasks(4);

        TaskInstanceRepository tiRepo = mock(TaskInstanceRepository.class);
        WorkflowInstanceRepository wiRepo = mock(WorkflowInstanceRepository.class);
        when(wiRepo.findById(wid)).thenReturn(Optional.of(wi));
        when(tiRepo.findByWorkflowInstanceId(wid))
                .thenReturn(List.of(n("SUCCESS"), n("SUCCESS"), n("SUCCESS"), n("FAILED")));
        when(wiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String state = new WorkflowStateService(tiRepo, wiRepo, mock(EventBus.class)).computeAndUpdate(wid).orElseThrow();

        assertThat(state).isEqualTo("FAILED");
        assertThat(wi.getCompletedTasks()).isEqualTo(3);
        assertThat(wi.getFailedTasks()).isEqualTo(1);
    }

    @Test
    void computeAndUpdate_TEST节点不计入计数() {
        UUID wid = UUID.randomUUID();
        WorkflowInstance wi = new WorkflowInstance();
        wi.setId(wid);
        wi.setState("RUNNING");
        wi.setCompletedTasks(0);
        wi.setFailedTasks(0);
        wi.setTotalTasks(2);

        TaskInstanceRepository tiRepo = mock(TaskInstanceRepository.class);
        WorkflowInstanceRepository wiRepo = mock(WorkflowInstanceRepository.class);
        when(wiRepo.findById(wid)).thenReturn(Optional.of(wi));
        when(tiRepo.findByWorkflowInstanceId(wid))
                .thenReturn(List.of(n("SUCCESS"), n("SUCCESS"), node("SUCCESS", "TEST")));
        when(wiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        new WorkflowStateService(tiRepo, wiRepo, mock(EventBus.class)).computeAndUpdate(wid);

        // TEST 试跑节点不参与计数，completed 只数两个 NORMAL SUCCESS
        assertThat(wi.getCompletedTasks()).isEqualTo(2);
    }

    @Test
    void computeAndUpdate_自然完成_补发workflowState事件_停止按钮方能收起() {
        // 回归 stop-button-stuck：自然聚合到 SUCCESS 时也须向 dw:evt:{id} 发 workflowState，
        // 否则前端 runStatus 永不到终态、停止按钮常驻。
        UUID wid = UUID.randomUUID();
        WorkflowInstance wi = new WorkflowInstance();
        wi.setId(wid);
        wi.setState("RUNNING");
        wi.setCompletedTasks(1);
        wi.setFailedTasks(0);
        wi.setTotalTasks(2);

        TaskInstanceRepository tiRepo = mock(TaskInstanceRepository.class);
        WorkflowInstanceRepository wiRepo = mock(WorkflowInstanceRepository.class);
        EventBus bus = mock(EventBus.class);
        when(wiRepo.findById(wid)).thenReturn(Optional.of(wi));
        when(tiRepo.findByWorkflowInstanceId(wid)).thenReturn(List.of(n("SUCCESS"), n("SUCCESS")));
        when(wiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        new WorkflowStateService(tiRepo, wiRepo, bus).computeAndUpdate(wid);

        verify(bus).publish(eq("dw:evt:" + wid), eq("{\"workflowState\":\"SUCCESS\"}"));
    }

    @Test
    void computeAndUpdate_聚合态未变_不发事件() {
        // 已是 SUCCESS 再算一次（无变化）→ 不重复发，避免无谓事件。
        UUID wid = UUID.randomUUID();
        WorkflowInstance wi = new WorkflowInstance();
        wi.setId(wid);
        wi.setState("SUCCESS");
        wi.setCompletedTasks(2);
        wi.setFailedTasks(0);
        wi.setTotalTasks(2);

        TaskInstanceRepository tiRepo = mock(TaskInstanceRepository.class);
        WorkflowInstanceRepository wiRepo = mock(WorkflowInstanceRepository.class);
        EventBus bus = mock(EventBus.class);
        when(wiRepo.findById(wid)).thenReturn(Optional.of(wi));
        when(tiRepo.findByWorkflowInstanceId(wid)).thenReturn(List.of(n("SUCCESS"), n("SUCCESS")));

        new WorkflowStateService(tiRepo, wiRepo, bus).computeAndUpdate(wid);

        verify(bus, never()).publish(any(), any());
    }
}
