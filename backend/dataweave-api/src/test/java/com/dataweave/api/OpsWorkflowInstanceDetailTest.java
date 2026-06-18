package com.dataweave.api;

import com.dataweave.master.application.OpsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/ops/workflow-instances/{id}（OpsService.workflowInstanceDetail）回归
 *（task-core-capabilities 验收缺口 1 后端部分）。
 *
 * <p>该端点原本缺失：实例详情视图调它一直 404 → 视图永远显示"工作流实例不存在"。
 * 补 endpoint 后应返回实例本身 + 其下任务节点（含 taskDefName 组装）。
 */
@SpringBootTest
@ActiveProfiles("h2")
class OpsWorkflowInstanceDetailTest {

    @Autowired
    OpsService opsService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void workflowInstanceDetail_返回实例及任务节点() {
        UUID wiId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO workflow_instance (id, tenant_id, project_id, workflow_id, state, biz_date, "
                        + "started_at, finished_at, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, 1, 'RUNNING', '2026-06-18', ?, NULL, ?, ?, 0, 0)",
                wiId, now, now, now);
        jdbc.update(
                "INSERT INTO task_instance (id, tenant_id, project_id, workflow_instance_id, task_id, "
                        + "run_mode, state, attempt, created_at, updated_at, deleted, version) "
                        + "VALUES (?, 1, 1, ?, 1, 'NORMAL', 'RUNNING', 0, ?, ?, 0, 0)",
                UUID.randomUUID(), wiId, now, now);

        OpsService.WorkflowInstanceDetail detail = opsService.workflowInstanceDetail(wiId);

        assertThat(detail).isNotNull();
        assertThat(detail.state()).isEqualTo("RUNNING");
        assertThat(detail.runMode()).isEqualTo("NORMAL");
        assertThat(detail.tasks()).hasSize(1);
        assertThat(detail.tasks().get(0).taskDefName()).isNotNull(); // task 1 名称
    }

    @Test
    void workflowInstanceDetail_不存在返回null() {
        assertThat(opsService.workflowInstanceDetail(UUID.fromString("00000000-0000-7000-8000-000000000099")))
                .isNull();
    }
}
