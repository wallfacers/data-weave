package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ProjectAuthz;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.CatalogAssignService;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.ScheduleParamResolver;
import com.dataweave.master.application.TaskService;
import com.dataweave.master.application.TaskService.TaskDetail;
import com.dataweave.master.application.TestRunCommand;
import com.dataweave.master.domain.TaskDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * task-run-decouple：POST /api/tasks/{id}/run 按发布态分流——
 * 已发布→TASK_RUN（NORMAL，忽略编辑器内容）；未发布→TEST_RUN（携带编辑器当前内容，经 TestRunCommand 编码）。
 * 两路均经 GatedActionService 闸门，不再对未发布任务 409 拒绝。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskControllerRunTest {

    @Mock private TaskService taskService;
    @Mock private ScheduleParamResolver paramResolver;
    @Mock private CatalogAssignService catalogAssignService;
    @Mock private GatedActionService gatedActionService;
    @Mock private ProjectAuthz projectAuthz;

    private TaskController controller;

    @BeforeEach
    void setUp() {
        controller = new TaskController(taskService, paramResolver, catalogAssignService, gatedActionService, projectAuthz);
        when(gatedActionService.submit(any(ActionRequest.class), any()))
                .thenReturn(new GateResult(GateResult.Outcome.EXECUTED, 1L, "L1", "ok", "s", false, null));
    }

    private TaskDetail detail(String status) {
        TaskDef t = new TaskDef();
        t.setId(7L);
        t.setName("订单宽表");
        t.setStatus(status);
        return new TaskDetail(t, List.of(), List.of());
    }

    private ServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/").build());
    }

    @Test
    void onlineTask_routesToTaskRun_normal_ignoringEditorContent() {
        when(taskService.getById(7L)).thenReturn(Optional.of(detail("ONLINE")));

        controller.run(7L, new TaskController.RunRequest("2026-06-20", "select 999", "SQL", null), exchange());

        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        org.mockito.Mockito.verify(gatedActionService).submit(cap.capture(), any());
        ActionRequest req = cap.getValue();
        assertThat(req.actionType()).isEqualTo("TASK_RUN");
        // NORMAL 路径 command 仅 bizDate（不携带编辑器内容）
        assertThat(req.command()).isEqualTo("2026-06-20");
    }

    @Test
    void draftTask_routesToTestRun_carryingEditorContent_noReject() {
        when(taskService.getById(7L)).thenReturn(Optional.of(detail("DRAFT")));

        controller.run(7L, new TaskController.RunRequest("2026-06-20", "select 1\nfrom t;", "SQL", "{\"a\":\"b\"}"),
                exchange());

        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        org.mockito.Mockito.verify(gatedActionService).submit(cap.capture(), any());
        ActionRequest req = cap.getValue();
        assertThat(req.actionType()).isEqualTo("TEST_RUN");
        // TEST 路径 command 携带编辑器内容，可解码回原值
        TestRunCommand.Decoded d = TestRunCommand.decode(req.command());
        assertThat(d.bizDate()).isEqualTo("2026-06-20");
        assertThat(d.content()).isEqualTo("select 1\nfrom t;");
        assertThat(d.type()).isEqualTo("SQL");
        assertThat(d.paramsJson()).isEqualTo("{\"a\":\"b\"}");
    }
}
