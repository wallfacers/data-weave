package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskDefVersion;
import com.dataweave.master.domain.TaskDefVersionRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkerNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 任务服务：完整 CRUD + 发布/下线生命周期。
 */
@Service
public class TaskService {

    private final TaskDefRepository taskDefRepository;
    private final TaskDefVersionRepository taskDefVersionRepository;
    private final TaskInstanceRepository taskInstanceRepository;
    private final FleetService fleetService;
    private final JdbcTemplate jdbcTemplate;

    public TaskService(TaskDefRepository taskDefRepository,
                       TaskDefVersionRepository taskDefVersionRepository,
                       TaskInstanceRepository taskInstanceRepository,
                       FleetService fleetService,
                       JdbcTemplate jdbcTemplate) {
        this.taskDefRepository = taskDefRepository;
        this.taskDefVersionRepository = taskDefVersionRepository;
        this.taskInstanceRepository = taskInstanceRepository;
        this.fleetService = fleetService;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ─── Records ─────────────────────────────────────────

    /** 创建即上线的返回值（兼容 MCP 工具 create_task）。 */
    public record TaskCreation(TaskDef task, String cron, java.util.UUID instanceId) {}

    /** 分页结果。 */
    public record PageResult(List<TaskDef> content, long totalElements, int totalPages, int page, int size) {}

    /** 任务详情（含版本历史）。 */
    public record TaskDetail(TaskDef task, List<TaskDefVersion> versions) {}

    // ─── Create（草稿）─────────────────────────────────────

    /** 创建任务定义（status=DRAFT）。 */
    public TaskDef create(TaskDef task) {
        LocalDateTime now = LocalDateTime.now();
        task.setTenantId(1L);
        task.setProjectId(1L);
        task.setStatus("DRAFT");
        task.setCurrentVersionNo(0);
        task.setHasDraftChange(1);
        if (task.getRetryMax() == null) task.setRetryMax(0);
        if (task.getPriority() == null) task.setPriority(5);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setDeleted(0);
        task.setVersion(0L);
        return taskDefRepository.save(task);
    }

    // ─── Search（分页搜索）─────────────────────────────────

    /** 分页搜索任务定义。 */
    public PageResult search(String keyword, String type, String status,
                             LocalDateTime startTime, LocalDateTime endTime,
                             int page, int size) {
        StringBuilder where = new StringBuilder("WHERE deleted = 0");
        List<Object> params = new ArrayList<>();

        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND name LIKE ?");
            params.add("%" + keyword.trim() + "%");
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND type = ?");
            params.add(type);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status);
        }
        if (startTime != null) {
            where.append(" AND created_at >= ?");
            params.add(startTime);
        }
        if (endTime != null) {
            where.append(" AND created_at <= ?");
            params.add(endTime);
        }

        // Count
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_def " + where, Long.class, params.toArray());
        long totalElements = total != null ? total : 0;

        // Page
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int offset = page * size;
        String sql = "SELECT * FROM task_def " + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add(offset);

        List<TaskDef> content = jdbcTemplate.query(sql, (rs, rowNum) -> {
            TaskDef t = new TaskDef();
            t.setId(rs.getLong("id"));
            t.setTenantId(rs.getLong("tenant_id"));
            t.setProjectId(rs.getLong("project_id"));
            t.setName(rs.getString("name"));
            t.setType(rs.getString("type"));
            t.setContent(rs.getString("content"));
            t.setDatasourceId(rs.getObject("datasource_id") != null ? rs.getLong("datasource_id") : null);
            t.setTargetDatasourceId(rs.getObject("target_datasource_id") != null ? rs.getLong("target_datasource_id") : null);
            t.setParamsJson(rs.getString("params_json"));
            t.setTimeoutSec(rs.getObject("timeout_sec") != null ? rs.getInt("timeout_sec") : null);
            t.setRetryMax(rs.getObject("retry_max") != null ? rs.getInt("retry_max") : null);
            t.setStatus(rs.getString("status"));
            t.setCurrentVersionNo(rs.getObject("current_version_no") != null ? rs.getInt("current_version_no") : null);
            t.setHasDraftChange(rs.getObject("has_draft_change") != null ? rs.getInt("has_draft_change") : null);
            t.setPriority(rs.getObject("priority") != null ? rs.getInt("priority") : null);
            t.setDescription(rs.getString("description"));
            t.setOwnerId(rs.getObject("owner_id") != null ? rs.getLong("owner_id") : null);
            t.setCreatedBy(rs.getObject("created_by") != null ? rs.getLong("created_by") : null);
            t.setUpdatedBy(rs.getObject("updated_by") != null ? rs.getLong("updated_by") : null);
            t.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null);
            t.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
            t.setDeleted(rs.getObject("deleted") != null ? rs.getInt("deleted") : null);
            t.setVersion(rs.getObject("version") != null ? rs.getLong("version") : null);
            return t;
        }, pageParams.toArray());

        return new PageResult(content, totalElements, totalPages, page, size);
    }

    // ─── GetById（含版本历史）─────────────────────────────

    /** 获取任务详情（含版本历史）。 */
    public Optional<TaskDetail> getById(Long id) {
        return taskDefRepository.findById(id).map(task -> {
            List<TaskDefVersion> versions = taskDefVersionRepository.findByTaskIdOrderByVersionNoDesc(id);
            return new TaskDetail(task, versions);
        });
    }

    // ─── Update（仅 DRAFT 可改）──────────────────────────

    /** 更新任务（仅 DRAFT 状态允许）。 */
    public TaskDef update(Long id, TaskDef patch) {
        TaskDef task = taskDefRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Task not found: " + id));
        if (!"DRAFT".equals(task.getStatus())) {
            throw new IllegalStateException("Task must be offline before editing");
        }
        if (patch.getName() != null) task.setName(patch.getName());
        if (patch.getType() != null) task.setType(patch.getType());
        if (patch.getContent() != null) task.setContent(patch.getContent());
        if (patch.getDatasourceId() != null) task.setDatasourceId(patch.getDatasourceId());
        if (patch.getTargetDatasourceId() != null) task.setTargetDatasourceId(patch.getTargetDatasourceId());
        if (patch.getParamsJson() != null) task.setParamsJson(patch.getParamsJson());
        if (patch.getTimeoutSec() != null) task.setTimeoutSec(patch.getTimeoutSec());
        if (patch.getRetryMax() != null) task.setRetryMax(patch.getRetryMax());
        if (patch.getPriority() != null) task.setPriority(patch.getPriority());
        if (patch.getDescription() != null) task.setDescription(patch.getDescription());
        if (patch.getOwnerId() != null) task.setOwnerId(patch.getOwnerId());
        task.setHasDraftChange(1);
        task.setUpdatedAt(LocalDateTime.now());
        return taskDefRepository.save(task);
    }

    // ─── SoftDelete ──────────────────────────────────────

    /** 软删除任务（仅 DRAFT 可删）。 */
    public void softDelete(Long id) {
        TaskDef task = taskDefRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Task not found: " + id));
        if (!"DRAFT".equals(task.getStatus())) {
            throw new IllegalStateException("Task must be offline before deletion");
        }
        task.setDeleted(1);
        task.setUpdatedAt(LocalDateTime.now());
        taskDefRepository.save(task);
    }

    // ─── Publish（DRAFT → ONLINE + 版本快照）─────────────

    /** 发布上线：DRAFT → ONLINE，生成版本快照。 */
    public TaskDef publish(Long id, String remark) {
        TaskDef task = taskDefRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Task not found: " + id));
        if ("ONLINE".equals(task.getStatus()) && (task.getHasDraftChange() == null || task.getHasDraftChange() == 0)) {
            throw new IllegalStateException("No draft changes to publish");
        }
        LocalDateTime now = LocalDateTime.now();
        int newVersion = (task.getCurrentVersionNo() != null ? task.getCurrentVersionNo() : 0) + 1;

        // 版本快照
        TaskDefVersion ver = new TaskDefVersion();
        ver.setTenantId(task.getTenantId());
        ver.setProjectId(task.getProjectId());
        ver.setTaskId(task.getId());
        ver.setVersionNo(newVersion);
        ver.setName(task.getName());
        ver.setType(task.getType());
        ver.setContent(task.getContent());
        ver.setDatasourceId(task.getDatasourceId());
        ver.setTargetDatasourceId(task.getTargetDatasourceId());
        ver.setParamsJson(task.getParamsJson());
        ver.setTimeoutSec(task.getTimeoutSec());
        ver.setRetryMax(task.getRetryMax());
        ver.setPriority(task.getPriority());
        ver.setDescription(task.getDescription());
        ver.setRemark(remark != null ? remark : "发布 v" + newVersion);
        ver.setPublishedAt(now);
        ver.setCreatedAt(now);
        taskDefVersionRepository.save(ver);

        task.setStatus("ONLINE");
        task.setCurrentVersionNo(newVersion);
        task.setHasDraftChange(0);
        task.setUpdatedAt(now);
        return taskDefRepository.save(task);
    }

    // ─── Offline（ONLINE → DRAFT）────────────────────────

    /** 下线：ONLINE → DRAFT。 */
    public TaskDef offline(Long id) {
        TaskDef task = taskDefRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Task not found: " + id));
        if (!"ONLINE".equals(task.getStatus())) {
            throw new IllegalStateException("Task is already offline");
        }
        task.setStatus("DRAFT");
        task.setUpdatedAt(LocalDateTime.now());
        return taskDefRepository.save(task);
    }

    // ─── 兼容旧方法（MCP create_task 调用）───────────────

    /** 创建任务定义（status=ONLINE）、版本快照 v1，并 mock 推进一条 SUCCESS 实例。 */
    public TaskCreation createAndOnline(String name, String type, String content, String cron) {
        LocalDateTime now = LocalDateTime.now();

        TaskDef task = new TaskDef();
        task.setTenantId(1L);
        task.setProjectId(1L);
        task.setName(name);
        task.setType(type);
        task.setContent(content);
        task.setStatus("ONLINE");
        task.setCurrentVersionNo(1);
        task.setHasDraftChange(0);
        task.setRetryMax(0);
        task.setPriority(5);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setDeleted(0);
        task.setVersion(0L);
        TaskDef saved = taskDefRepository.save(task);

        TaskDefVersion ver = new TaskDefVersion();
        ver.setTenantId(1L);
        ver.setProjectId(1L);
        ver.setTaskId(saved.getId());
        ver.setVersionNo(1);
        ver.setName(name);
        ver.setType(type);
        ver.setContent(content);
        ver.setPriority(5);
        ver.setRemark("初始发布");
        ver.setPublishedAt(now);
        ver.setCreatedAt(now);
        taskDefVersionRepository.save(ver);

        String nodeCode = fleetService.pickLeastLoadedOnline()
                .map(WorkerNode::getNodeCode).orElse(null);
        TaskInstance instance = new TaskInstance();
        instance.setTenantId(1L);
        instance.setProjectId(1L);
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

        return new TaskCreation(saved, cron, savedInstance.getId());
    }
}
