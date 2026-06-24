package com.dataweave.api.application;

import com.dataweave.api.interfaces.dto.*;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * DataOpsBridge 的桩实现：编译期让 Stream C 独立编译通过；集成期替换为委托到 Stream A 真服务的实现。
 *
 * <p>查询方法直接用现有 repository 做简单筛选/分页；写方法全部抛 {@link UnsupportedOperationException}，
 * 因为写操作必须经 {@code GatedActionService} 闸门，桩不模拟领域逻辑。
 */
@Component
public class DataOpsBridgeStub implements DataOpsBridge {

    private static final Logger log = LoggerFactory.getLogger(DataOpsBridgeStub.class);

    private final TaskInstanceRepository instanceRepository;
    private final TaskDefRepository taskDefRepository;

    public DataOpsBridgeStub(TaskInstanceRepository instanceRepository,
                             TaskDefRepository taskDefRepository) {
        this.instanceRepository = instanceRepository;
        this.taskDefRepository = taskDefRepository;
    }

    @Override
    public BackfillRun submitBackfill(BackfillRequest req) {
        throw new UnsupportedOperationException("submitBackfill — 待 Stream A 实现");
    }

    @Override
    public BatchResult batchOp(List<UUID> instanceIds, BatchOp op) {
        throw new UnsupportedOperationException("batchOp — 待 Stream A 实现");
    }

    @Override
    public TaskInstance setSuccess(UUID instanceId) {
        throw new UnsupportedOperationException("setSuccess — 待 Stream A 实现");
    }

    @Override
    public TaskDef setFrozen(Long taskDefId, boolean frozen) {
        throw new UnsupportedOperationException("setFrozen — 待 Stream A 实现");
    }

    @Override
    public Page<InstanceRow> queryInstances(InstanceQuery q) {
        // 预加载 taskDef name 映射
        Map<Long, String> taskNames = new java.util.HashMap<>();
        taskDefRepository.findAll().forEach(td -> taskNames.put(td.getId(), td.getName()));

        List<TaskInstance> all = new ArrayList<>();
        instanceRepository.findAll().forEach(all::add);

        var stream = all.stream()
                .filter(i -> "NORMAL".equals(i.getRunMode()) || "BACKFILL".equals(i.getRunMode()));

        if (q.runMode() != null && !q.runMode().isBlank()) {
            stream = stream.filter(i -> q.runMode().equals(i.getRunMode()));
        }
        if (q.state() != null && !q.state().isBlank()) {
            stream = stream.filter(i -> q.state().equals(i.getState()));
        }
        if (q.taskId() != null) {
            stream = stream.filter(i -> q.taskId().equals(i.getTaskId()));
        }
        if (q.bizDate() != null && !q.bizDate().isBlank()) {
            stream = stream.filter(i -> q.bizDate().equals(i.getBizDate()));
        }

        List<TaskInstance> filtered = stream
                .sorted(Comparator.comparing(TaskInstance::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        long total = filtered.size();
        int from = (q.page() - 1) * q.size();
        int to = Math.min(from + q.size(), filtered.size());
        List<TaskInstance> page = from < filtered.size() ? filtered.subList(from, to) : List.of();

        List<InstanceRow> rows = page.stream()
                .map(ti -> toRow(ti, taskNames))
                .collect(Collectors.toList());

        return new Page<>(rows, total, q.page(), q.size());
    }

    @Override
    public BackfillRun backfillRun(UUID runId) {
        throw new UnsupportedOperationException("backfillRun — 待 Stream A 实现");
    }

    @Override
    public List<BackfillRun> backfillRuns(int page, int size) {
        throw new UnsupportedOperationException("backfillRuns — 待 Stream A 实现");
    }

    @Override
    public List<InstanceRow> backfillRunInstances(UUID runId) {
        throw new UnsupportedOperationException("backfillRunInstances — 待 Stream A 实现");
    }

    private InstanceRow toRow(TaskInstance ti, Map<Long, String> taskNames) {
        String name = taskNames.getOrDefault(ti.getTaskId(), "task-" + ti.getTaskId());
        Long durationMs = null;
        if (ti.getStartedAt() != null && ti.getFinishedAt() != null) {
            durationMs = Duration.between(ti.getStartedAt(), ti.getFinishedAt()).toMillis();
        }
        return new InstanceRow(
                ti.getId(),
                ti.getTaskId(),
                name,
                null, // workflowId: TaskInstance 无此直接字段，待 Stream A 真实现
                ti.getRunMode(),
                ti.getState(),
                ti.getBizDate(),
                ti.getStartedAt(),
                ti.getFinishedAt(),
                durationMs
        );
    }
}
