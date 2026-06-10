package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskDefVersion;
import com.dataweave.master.domain.TaskDefVersionRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkerNode;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 任务服务：创建任务定义并直接上线，同时建版本快照与 mock 成功运行实例。
 */
@Service
public class TaskService {

    private final TaskDefRepository taskDefRepository;
    private final TaskDefVersionRepository taskDefVersionRepository;
    private final TaskInstanceRepository taskInstanceRepository;
    private final FleetService fleetService;

    public TaskService(TaskDefRepository taskDefRepository,
                       TaskDefVersionRepository taskDefVersionRepository,
                       TaskInstanceRepository taskInstanceRepository,
                       FleetService fleetService) {
        this.taskDefRepository = taskDefRepository;
        this.taskDefVersionRepository = taskDefVersionRepository;
        this.taskInstanceRepository = taskInstanceRepository;
        this.fleetService = fleetService;
    }

    /**
     * 创建任务定义（status=ONLINE）、版本快照 v1，并 mock 推进一条 SUCCESS 实例。
     * cron 属于工作流级，TaskDef 不存 cron，此处仅透传回给上层展示。
     */
    public record TaskCreation(TaskDef task, String cron, Long instanceId) {
    }

    public TaskCreation createAndOnline(String name, String type, String content, String cron) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 建 TaskDef
        TaskDef task = new TaskDef();
        task.setTenantId(1L);   // MVP 默认租户/项目
        task.setProjectId(1L);  // MVP 默认租户/项目
        task.setName(name);
        task.setType(type);
        task.setContent(content);
        task.setStatus("ONLINE");
        task.setCurrentVersionNo(1);
        task.setHasDraftChange(0);
        task.setRetryMax(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setDeleted(0);
        task.setVersion(0L);
        TaskDef saved = taskDefRepository.save(task);

        // 2. 建 TaskDefVersion 快照 v1
        TaskDefVersion ver = new TaskDefVersion();
        ver.setTenantId(1L);    // MVP 默认租户/项目
        ver.setProjectId(1L);   // MVP 默认租户/项目
        ver.setTaskId(saved.getId());
        ver.setVersionNo(1);
        ver.setName(name);
        ver.setType(type);
        ver.setContent(content);
        ver.setRemark("初始发布");
        ver.setPublishedAt(now);
        ver.setCreatedAt(now);
        taskDefVersionRepository.save(ver);

        // 3. mock 一条成功 TaskInstance
        String nodeCode = fleetService.pickLeastLoadedOnline()
                .map(WorkerNode::getNodeCode).orElse(null);
        TaskInstance instance = new TaskInstance();
        instance.setTenantId(1L);   // MVP 默认租户/项目
        instance.setProjectId(1L);  // MVP 默认租户/项目
        instance.setTaskId(saved.getId());
        instance.setTaskVersionNo(1);
        instance.setRunMode("NORMAL");
        instance.setState("SUCCESS");
        instance.setAttempt(1);
        instance.setWorkerNodeCode(nodeCode);
        instance.setStartedAt(now);
        instance.setFinishedAt(now);
        instance.setLog("[mock] 任务执行成功" + (nodeCode != null ? "，落在 " + nodeCode : ""));
        instance.setCreatedAt(now);
        instance.setUpdatedAt(now);
        instance.setDeleted(0);
        instance.setVersion(0L);
        TaskInstance savedInstance = taskInstanceRepository.save(instance);

        // 4. 返回（cron 仅透传回上层展示，不存 TaskDef）
        return new TaskCreation(saved, cron, savedInstance.getId());
    }
}
