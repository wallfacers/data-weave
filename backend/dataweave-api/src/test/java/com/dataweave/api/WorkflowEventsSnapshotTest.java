package com.dataweave.api;

import com.dataweave.api.interfaces.OpsController;
import com.dataweave.master.domain.EventBus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/ops/workflow-instances/{id}/events/stream 连接时快照回归：
 * dw:evt 是纯 Redis/内存 pub/sub 无回放，实例跑得比"建连 + 订阅注册"这段握手还快时
 * （本地/mock 任务常见毫秒级完成），若不补发快照，前端会永久停留在初始 WAITING/RUNNING 态
 * （用户实测：一条 6 节点 SHELL 流水线全程 <400ms 跑完，画布卡在「等待」/「运行中」不再更新）。
 *
 * <p>验证：即使全程不发布任何实时事件，订阅即应收到与 DB 当前态一致的快照（各任务 taskState +
 * 工作流自身 workflowState）；快照之后仍能续接实时事件。
 */
@SpringBootTest
@ActiveProfiles("h2")
class WorkflowEventsSnapshotTest {

    private static final Duration BOUND = Duration.ofSeconds(15);

    @Autowired OpsController opsController;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventBus eventBus;

    /** 建一个 workflow_instance + N 个 task_instance，各自指定态。 */
    private UUID insertWorkflow(String wfState, String... taskStates) {
        UUID wfId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, biz_date, "
                        + "started_at, finished_at, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, 1, ?, '2026-07-02', ?, NULL, ?, ?, 0, 0)",
                wfId, wfState, now, now, now);
        for (String state : taskStates) {
            jdbc.update(
                    "INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, task_id, "
                            + "run_mode, state, attempt, created_at, updated_at, deleted, version) "
                            + "VALUES (?, 1, 1, ?, 1, 'NORMAL', ?, 0, ?, ?, 0, 0)",
                    UUID.randomUUID(), wfId, state, now, now);
        }
        return wfId;
    }

    @Test
    void 连接即收到与DB当前态一致的快照_全程无实时事件() {
        UUID wfId = insertWorkflow("SUCCESS", "SUCCESS", "WAITING");

        // 3 = 2 个 task 快照 + 1 个 workflow 自身快照；take(3) 精确取到快照即取消，不依赖任何 sleep。
        List<ServerSentEvent<String>> events =
                opsController.workflowEventsStream(wfId).take(3).collectList().block(BOUND);

        assertThat(events).isNotNull().hasSize(3);
        assertThat(events).allMatch(e -> "status".equals(e.event()));
        assertThat(events).extracting(ServerSentEvent::data)
                .anyMatch(d -> d.contains("\"taskState\":\"SUCCESS\""))
                .anyMatch(d -> d.contains("\"taskState\":\"WAITING\""))
                .anyMatch(d -> d.contains("\"workflowState\":\"SUCCESS\""));
    }

    @Test
    void 快照之后仍续接实时事件() {
        UUID wfId = insertWorkflow("RUNNING", "RUNNING");

        List<ServerSentEvent<String>> events = opsController.workflowEventsStream(wfId)
                .take(3) // 1 个 task 快照 + 1 个 workflow 快照 + 1 个后续实时事件
                .doOnSubscribe(s -> eventBus.publish("dw:evt:" + wfId,
                        "{\"workflowState\":\"SUCCESS\"}"))
                .collectList().block(BOUND);

        assertThat(events).isNotNull().hasSize(3);
        // 快照给的是订阅那一刻 DB 里的 RUNNING，实时事件补发的是随后发布的 SUCCESS —— 两者都应出现。
        assertThat(events).extracting(ServerSentEvent::data)
                .anyMatch(d -> d.contains("\"workflowState\":\"RUNNING\""))
                .anyMatch(d -> d.contains("\"workflowState\":\"SUCCESS\""));
    }

    @Test
    void 实例不存在_不报错_无快照事件() {
        UUID wfId = UUID.randomUUID();
        // 无快照、无实时事件：有界等待一段时间后应仍为空（不抛异常、不挂起）。
        List<ServerSentEvent<String>> events = opsController.workflowEventsStream(wfId)
                .take(Duration.ofMillis(300)).collectList().block(BOUND);
        assertThat(events).isNotNull().isEmpty();
    }
}
