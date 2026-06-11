package com.dataweave.api;

import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证实例类 UUIDv7 客户端赋值主键的持久化语义（distributed-scheduler-m1 · 1.1）：
 * 新实例 id 为 null 时由 {@code BeforeConvertCallback} 赋 UUIDv7 并 INSERT；
 * 已加载实例（id 非空）再保存走 UPDATE，不产生重复行。
 */
@SpringBootTest
@ActiveProfiles("h2")
class InstancePersistenceTest {

    @Autowired
    TaskInstanceRepository taskInstanceRepository;

    @Autowired
    WorkflowInstanceRepository workflowInstanceRepository;

    @Test
    void newTaskInstance_getsUuidv7Assigned_andInserts() {
        TaskInstance ti = new TaskInstance();
        ti.setTenantId(1L);
        ti.setProjectId(1L);
        ti.setTaskId(1L);
        ti.setRunMode("NORMAL");
        ti.setState("WAITING");
        ti.setAttempt(0);
        ti.setCreatedAt(LocalDateTime.now());
        ti.setUpdatedAt(LocalDateTime.now());
        ti.setDeleted(0);
        ti.setVersion(0L);
        assertThat(ti.getId()).isNull();

        TaskInstance saved = taskInstanceRepository.save(ti);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getId().version()).isEqualTo(7);
        UUID id = saved.getId();
        assertThat(taskInstanceRepository.findById(id)).isPresent();
    }

    @Test
    void reSavingLoadedInstance_updatesNotDuplicates() {
        long before = taskInstanceRepository.count();

        TaskInstance ti = new TaskInstance();
        ti.setTenantId(1L);
        ti.setProjectId(1L);
        ti.setTaskId(1L);
        ti.setRunMode("NORMAL");
        ti.setState("WAITING");
        ti.setAttempt(0);
        ti.setCreatedAt(LocalDateTime.now());
        ti.setUpdatedAt(LocalDateTime.now());
        ti.setDeleted(0);
        ti.setVersion(0L);
        UUID id = taskInstanceRepository.save(ti).getId();
        assertThat(taskInstanceRepository.count()).isEqualTo(before + 1);

        TaskInstance loaded = taskInstanceRepository.findById(id).orElseThrow();
        loaded.setState("RUNNING");
        loaded.setUpdatedAt(LocalDateTime.now());
        taskInstanceRepository.save(loaded);

        // 再保存为 UPDATE：行数不变，状态已更新
        assertThat(taskInstanceRepository.count()).isEqualTo(before + 1);
        assertThat(taskInstanceRepository.findById(id).orElseThrow().getState()).isEqualTo("RUNNING");
    }

    @Test
    void newWorkflowInstance_getsUuidv7Assigned() {
        WorkflowInstance wi = new WorkflowInstance();
        wi.setTenantId(1L);
        wi.setProjectId(1L);
        wi.setWorkflowId(1L);
        wi.setTriggerType("MANUAL");
        wi.setState("RUNNING");
        wi.setBizDate("2026-06-11");
        wi.setTotalTasks(0);
        wi.setCompletedTasks(0);
        wi.setFailedTasks(0);
        wi.setCreatedAt(LocalDateTime.now());
        wi.setUpdatedAt(LocalDateTime.now());
        wi.setDeleted(0);
        wi.setVersion(0L);

        WorkflowInstance saved = workflowInstanceRepository.save(wi);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getId().version()).isEqualTo(7);
        assertThat(workflowInstanceRepository.findById(saved.getId())).isPresent();
    }
}
