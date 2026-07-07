package com.dataweave.api.application;

import com.dataweave.api.interfaces.dto.BackfillRequest;
import com.dataweave.api.interfaces.dto.BackfillRun;
import com.dataweave.api.interfaces.dto.BatchOp;
import com.dataweave.api.interfaces.dto.BatchResult;
import com.dataweave.api.interfaces.dto.InstanceQuery;
import com.dataweave.api.interfaces.dto.InstanceRow;
import com.dataweave.api.interfaces.dto.Page;
import com.dataweave.master.application.BackfillService;
import com.dataweave.master.application.OpsContracts;
import com.dataweave.master.application.OpsService;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskInstance;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link DataOpsBridge} 的真实现（集成期 @Primary 覆盖 {@link DataOpsBridgeStub}）：
 * 委托到 Stream A 的 {@link OpsService}/{@link BackfillService}，并做 接口层 DTO ↔ master {@code OpsContracts} 映射。
 *
 * <p>映射要点（A↔C 契约对齐）：
 * <ul>
 *   <li><b>分页基准</b>：C 的 dto 分页从 1 起（{@code InstanceQuery} 紧凑构造夹取 ≥1），master 服务从 0 起 → 此处 {@code page-1}。</li>
 *   <li><b>时间类型</b>：master {@code InstanceRow}/{@code BackfillRunView} 时间为 ISO 字符串，C dto 为 {@code LocalDateTime} → 此处解析。</li>
 *   <li><b>workflowId</b>：master 行携带的是 workflow_instance UUID，C dto 需要 workflow_def Long；M1 暂置 null（列表展示不强依赖）。</li>
 * </ul>
 *
 * <p>写操作的闸门由 controller 前置（per-item {@code GatedActionService.submit}）；本桥接只做领域动作。
 * {@link #batchOp} 控制器未直接调用（批量经闸门执行器 {@code OPS_*} 动作），此处仍实现以满足接口完整性。
 */
@Component
@Primary
public class DataOpsBridgeRealImpl implements DataOpsBridge {

    private final OpsService opsService;
    private final BackfillService backfillService;
    private final com.dataweave.master.application.NodeFreezeService nodeFreezeService;

    public DataOpsBridgeRealImpl(OpsService opsService, BackfillService backfillService,
                                 com.dataweave.master.application.NodeFreezeService nodeFreezeService) {
        this.opsService = opsService;
        this.backfillService = backfillService;
        this.nodeFreezeService = nodeFreezeService;
    }

    @Override
    public BackfillRun submitBackfill(BackfillRequest req) {
        OpsContracts.BackfillRunView v = backfillService.submitBackfill(
                new OpsContracts.BackfillRequest(req.targetType(), req.targetId(),
                        req.dateStart(), req.dateEnd(), req.includeDownstream(), req.parallelism(),
                        req.downstreamTaskIds()));
        return toDtoRun(v);
    }

    @Override
    public List<OpsContracts.DownstreamTaskView> previewDownstream(String targetType, Long targetId) {
        return backfillService.previewDownstream(targetType, targetId);
    }

    @Override
    public BatchResult batchOp(List<UUID> instanceIds, BatchOp op) {
        List<BatchResult.BatchResultItem> items = new ArrayList<>();
        int accepted = 0;
        for (UUID id : instanceIds) {
            try {
                switch (op) {
                    case RERUN -> opsService.rerunInstance(id);
                    case KILL -> opsService.killTask(id);
                    case SET_SUCCESS -> opsService.setSuccess(id);
                }
                items.add(new BatchResult.BatchResultItem(id.toString(), "EXECUTED", null));
                accepted++;
            } catch (RuntimeException e) {
                items.add(new BatchResult.BatchResultItem(id.toString(), "REJECTED", null));
            }
        }
        return new BatchResult(instanceIds.size(), accepted, items);
    }

    @Override
    public TaskInstance setSuccess(UUID instanceId) {
        return opsService.setSuccess(instanceId);
    }


    @Override
    public void setNodeFrozen(Long workflowId, String nodeKey, UUID instanceId, boolean frozen) {
        // tenant/project 固定 1/1（与现有运维写一致）；actor 透传留痕由 controller 闸门负责。
        nodeFreezeService.setFrozen(workflowId, nodeKey, instanceId, frozen, 1L, 1L, null);
    }

    @Override
    public Page<InstanceRow> queryInstances(InstanceQuery q) {
        OpsContracts.PageResult<OpsContracts.InstanceRow> pr = opsService.queryInstances(
                new OpsContracts.InstanceQuery(q.runMode(), q.state(), q.taskId(), q.bizDate(),
                        q.stateIn(), q.bizDateFrom(), q.bizDateTo(), q.startedAtFrom(), q.startedAtTo(),
                        q.workerNodeCode(), q.failureReason(), q.projectId(), q.workflowInstanceId(),
                        q.keyword(), q.sortField(), q.sortDir(), Math.max(0, q.page() - 1), q.size()));
        List<InstanceRow> rows = pr.items().stream().map(DataOpsBridgeRealImpl::toDtoRow).toList();
        return new Page<>(rows, pr.total(), q.page(), q.size());
    }

    @Override
    public BackfillRun backfillRun(UUID runId) {
        // 不存在 → null（端点宽松返回 200 + run:null，与 C 契约/前端兜底一致），不抛 409。
        try {
            return toDtoRun(backfillService.backfillRun(runId).run());
        } catch (IllegalStateException notFound) {
            return null;
        }
    }

    @Override
    public List<BackfillRun> backfillRuns(int page, int size, Long projectId) {
        return backfillService.backfillRuns(Math.max(0, page - 1), size, projectId).stream()
                .map(DataOpsBridgeRealImpl::toDtoRun).toList();
    }

    @Override
    public Page<BackfillRun> queryBackfillRuns(String state, String targetName, String targetType,
                                               String bizDateFrom, String bizDateTo, Long createdBy,
                                               Long projectId, int page, int size) {
        OpsContracts.PageResult<OpsContracts.BackfillRunView> pr = backfillService.queryBackfillRuns(
                state, targetName, targetType, bizDateFrom, bizDateTo, createdBy, projectId,
                Math.max(0, page - 1), size);
        List<BackfillRun> rows = pr.items().stream().map(DataOpsBridgeRealImpl::toDtoRun).toList();
        return new Page<>(rows, pr.total(), page, size);
    }

    @Override
    public List<InstanceRow> backfillRunInstances(UUID runId) {
        try {
            return backfillService.backfillRun(runId).instances().stream()
                    .map(DataOpsBridgeRealImpl::toDtoRow).toList();
        } catch (IllegalStateException notFound) {
            return List.of();
        }
    }

    // ─── 003-instance-dag-viewer 新方法 ────────────────────

    @Override
    public Page<com.dataweave.master.application.OpsContracts.WorkflowInstanceRow> queryWorkflowInstances(
            com.dataweave.master.application.OpsContracts.WorkflowInstanceQuery q) {
        OpsContracts.PageResult<OpsContracts.WorkflowInstanceRow> pr = opsService.queryWorkflowInstances(
                new OpsContracts.WorkflowInstanceQuery(q.state(), q.stateIn(), q.triggerType(),
                        q.workflowId(), q.bizDate(), q.bizDateFrom(), q.bizDateTo(),
                        q.startedAtFrom(), q.startedAtTo(),
                        q.scheduledFireTimeFrom(), q.scheduledFireTimeTo(),
                        q.projectId(), q.sortField(), q.sortDir(),
                        Math.max(0, q.page() - 1), q.size()));
        return new Page<>(pr.items(), pr.total(), q.page(), q.size());
    }

    @Override
    public com.dataweave.master.application.OpsContracts.InstanceDagView getInstanceDag(UUID workflowInstanceId) {
        return opsService.getInstanceDag(workflowInstanceId);
    }

    @Override
    public com.dataweave.master.application.OpsContracts.ResolvedCodeView getResolvedCode(UUID taskInstanceId) {
        return opsService.resolveActualCode(taskInstanceId);
    }

    @Override
    public com.dataweave.master.application.OpsContracts.ResolvedConfigView getResolvedConfig(UUID taskInstanceId) {
        return opsService.resolveActualConfig(taskInstanceId);
    }

    // ─── 映射 ───────────────────────────────────────────

    private static InstanceRow toDtoRow(OpsContracts.InstanceRow r) {
        return new InstanceRow(r.id(), r.taskDefId(), r.taskDefName(),
                null, // workflowId（workflow_def Long）：master 行携带的是实例 UUID，M1 暂不映射
                r.workflowInstanceId(),
                r.runMode(), r.state(), r.bizDate(),
                parseDt(r.startedAt()), parseDt(r.finishedAt()), r.durationMs(),
                r.cronExpression(), r.env(), r.workflowName(),
                parseDt(r.scheduledFireTime()),
                r.triggerType());
    }

    private static BackfillRun toDtoRun(OpsContracts.BackfillRunView v) {
        return new BackfillRun(v.id(), v.targetType(), v.targetId(), v.targetName(),
                v.dateStart(), v.dateEnd(), v.parallelism(), v.state(),
                v.total(), v.success(), v.failed(), v.running(), parseDt(v.createdAt()),
                v.activeDates(), v.heldDates());
    }

    private static LocalDateTime parseDt(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (java.time.format.DateTimeParseException e) {
            return LocalDateTime.parse(iso);
        }
    }
}
