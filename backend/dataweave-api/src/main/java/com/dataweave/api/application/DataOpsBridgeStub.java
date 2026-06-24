package com.dataweave.api.application;

import com.dataweave.api.interfaces.dto.*;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import jakarta.annotation.PostConstruct;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * DataOpsBridge 的桩实现：编译期让 Stream C 独立编译通过；集成期替换为委托到 Stream A 真服务的实现。
 *
 * <p>查询方法直接用现有 repository 做简单筛选/分页；写方法全部抛 {@link UnsupportedOperationException}，
 * 因为写操作必须经 {@code GatedActionService} 闸门，桩不模拟领域逻辑。
 *
 * <p><strong>安全注意：</strong>此桩仅用于开发/编译期；生产环境 MUST 由 Stream A 的真实现替代。
 * Stream A 的真实现负责按当前租户/项目过滤（{@code tenantId}/{@code projectId} 从安全上下文获取），
 * 此桩不做租户隔离——它始终返回所有实例。集成后 {@code @Primary} 标注的真实现自动覆盖此桩。
 * 若此桩意外激活在生产环境，运行时会因写操作抛 {@link UnsupportedOperationException} 而快速失败，
 * 不会静默绕过租户隔离。
 */
@Component
public class DataOpsBridgeStub implements DataOpsBridge {

    private static final Logger log = LoggerFactory.getLogger(DataOpsBridgeStub.class);

    private final TaskInstanceRepository instanceRepository;
    private final TaskDefRepository taskDefRepository;
    private final AtomicBoolean warnedQuery = new AtomicBoolean(false);

    public DataOpsBridgeStub(TaskInstanceRepository instanceRepository,
                             TaskDefRepository taskDefRepository) {
        this.instanceRepository = instanceRepository;
        this.taskDefRepository = taskDefRepository;
    }

    /**
     * 启动时显式警告：此桩不提供租户隔离。若在生产环境看到此日志，说明 Stream A 的真实现未正确覆盖。
     */
    @PostConstruct
    void warnOnStartup() {
        log.warn("================================================================");
        log.warn("DataOpsBridgeStub 已激活 — 仅用于开发/编译期，不提供租户隔离。");
        log.warn("生产环境 MUST 由 Stream A 的 DataOpsBridge 真实现（@Primary）覆盖此桩。");
        log.warn("================================================================");
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
        // 首次调用时额外警告：此桩不做租户过滤，返回全量数据
        if (warnedQuery.compareAndSet(false, true)) {
            log.warn("DataOpsBridgeStub.queryInstances 被调用 — 此桩不做租户隔离，返回所有租户的实例。"
                    + "若在非开发环境看到此日志，请立即检查 DataOpsBridge 真实现是否正确注入。");
        }
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
