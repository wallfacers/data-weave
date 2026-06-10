package com.dataweave.master.application;

import com.dataweave.master.domain.TaskInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流状态聚合矩阵单测（design-data-model.md §4）。聚合是纯函数，免 Spring 上下文。
 */
class WorkflowStateServiceTest {

    private final WorkflowStateService service = new WorkflowStateService(null, null);

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
}
