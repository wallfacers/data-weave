package com.dataweave.master.application;

import com.dataweave.master.domain.Task;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 任务服务：从解析出的定义建任务、上线（DRAFT -> ONLINE），并 mock 推进一条实例至 SUCCESS。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskInstanceRepository taskInstanceRepository;

    public TaskService(TaskRepository taskRepository, TaskInstanceRepository taskInstanceRepository) {
        this.taskRepository = taskRepository;
        this.taskInstanceRepository = taskInstanceRepository;
    }

    /**
     * 创建任务并直接上线（status=ONLINE），同时 mock 推进一条 task_instance 至 SUCCESS。
     */
    public Task createAndOnline(String name, String type, String content, String cron) {
        Task task = new Task();
        task.setName(name);
        task.setType(type);
        task.setContent(content);
        task.setCron(cron);
        task.setStatus("ONLINE");
        task.setCreatedAt(LocalDateTime.now());
        Task saved = taskRepository.save(task);

        // mock 调度推进：直接产生一条已成功的运行实例
        TaskInstance instance = new TaskInstance();
        instance.setTaskId(saved.getId());
        instance.setState("SUCCESS");
        instance.setStartedAt(LocalDateTime.now());
        instance.setFinishedAt(LocalDateTime.now());
        instance.setLog("[mock] 任务执行成功（MVP 阶段调度执行为 mock 推进）");
        taskInstanceRepository.save(instance);

        return saved;
    }
}
