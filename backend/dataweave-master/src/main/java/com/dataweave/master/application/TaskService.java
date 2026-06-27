package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskDefVersion;
import com.dataweave.master.domain.TaskDefVersionRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkflowNode;
import com.dataweave.master.domain.WorkflowNodeRepository;
import com.dataweave.master.i18n.BizException;
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
    private final WorkflowNodeRepository workflowNodeRepository;
    private final FleetService fleetService;
    private final JdbcTemplate jdbcTemplate;
    private final LineageGraphService lineageGraphService;
    private final SqlTableExtractor sqlTableExtractor;

    public TaskService(TaskDefRepository taskDefRepository,
                       TaskDefVersionRepository taskDefVersionRepository,
                       TaskInstanceRepository taskInstanceRepository,
                       WorkflowNodeRepository workflowNodeRepository,
                       FleetService fleetService,
                       JdbcTemplate jdbcTemplate,
                       LineageGraphService lineageGraphService,
                       SqlTableExtractor sqlTableExtractor) {
        this.taskDefRepository = taskDefRepository;
        this.taskDefVersionRepository = taskDefVersionRepository;
        this.taskInstanceRepository = taskInstanceRepository;
        this.workflowNodeRepository = workflowNodeRepository;
        this.fleetService = fleetService;
        this.jdbcTemplate = jdbcTemplate;
        this.lineageGraphService = lineageGraphService;
        this.sqlTableExtractor = sqlTableExtractor;
    }

    // ─── Records ─────────────────────────────────────────

    /** 创建即上线的返回值（兼容 MCP 工具 create_task）。 */
    public record TaskCreation(TaskDef task, String cron, java.util.UUID instanceId) {}

    /** 分页结果。 */
    public record PageResult(List<TaskDef> content, long totalElements, int totalPages, int page, int size) {}

    /** 任务详情（含版本历史）。 */
    public record TaskDetail(TaskDef task, List<TaskDefVersion> versions,
                             List<String> referencedByOnlineWorkflows) {}

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

    /** 分页搜索任务定义（兼容旧签名，无类目/标签过滤）。 */
    public PageResult search(String keyword, String type, String status,
                             LocalDateTime startTime, LocalDateTime endTime,
                             int page, int size) {
        return search(keyword, type, status, startTime, endTime, null, false, null, null, null, null, page, size);
    }

    /**
     * 分页搜索任务定义，支持类目/标签过滤。
     *
     * @param catalogNodeId 归属文件夹过滤（null 且 uncategorized=false 时不过滤）
     * @param uncategorized true 仅返回未归类（catalog_node_id IS NULL）
     * @param tagId         标签过滤（null 不过滤）
     */
    public PageResult search(String keyword, String type, String status,
                             LocalDateTime startTime, LocalDateTime endTime,
                             Long catalogNodeId, boolean uncategorized, Long tagId,
                             int page, int size) {
        return search(keyword, type, status, startTime, endTime, catalogNodeId, uncategorized, tagId,
                null, null, null, page, size);
    }

    /**
     * 分页搜索任务定义，支持类目/标签/负责人/冻结/数据源过滤。
     *
     * @param catalogNodeId 归属文件夹过滤（null 且 uncategorized=false 时不过滤）
     * @param uncategorized true 仅返回未归类（catalog_node_id IS NULL）
     * @param tagId         标签过滤（null 不过滤）
     * @param ownerId       负责人过滤（null 不过滤）
     * @param frozen        冻结过滤（null 不过滤；1=冻结，0=未冻结）
     * @param datasourceId  数据源过滤（null 不过滤）
     */
    public PageResult search(String keyword, String type, String status,
                             LocalDateTime startTime, LocalDateTime endTime,
                             Long catalogNodeId, boolean uncategorized, Long tagId,
                             Long ownerId, Integer frozen, Long datasourceId,
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
        if (uncategorized) {
            where.append(" AND catalog_node_id IS NULL");
        } else if (catalogNodeId != null) {
            where.append(" AND catalog_node_id = ?");
            params.add(catalogNodeId);
        }
        if (tagId != null) {
            where.append(" AND id IN (SELECT entity_id FROM entity_tag WHERE tag_id = ? AND entity_type = 'TASK')");
            params.add(tagId);
        }
        if (ownerId != null) {
            where.append(" AND owner_id = ?");
            params.add(ownerId);
        }
        if (frozen != null) {
            where.append(" AND frozen = ?");
            params.add(frozen);
        }
        if (datasourceId != null) {
            where.append(" AND datasource_id = ?");
            params.add(datasourceId);
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
            t.setFrozen(rs.getObject("frozen") != null ? rs.getInt("frozen") : null);
            t.setDescription(rs.getString("description"));
            t.setOwnerId(rs.getObject("owner_id") != null ? rs.getLong("owner_id") : null);
            t.setCreatedBy(rs.getObject("created_by") != null ? rs.getLong("created_by") : null);
            t.setUpdatedBy(rs.getObject("updated_by") != null ? rs.getLong("updated_by") : null);
            t.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null);
            t.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
            t.setDeleted(rs.getObject("deleted") != null ? rs.getInt("deleted") : null);
            t.setVersion(rs.getObject("version") != null ? rs.getLong("version") : null);
            t.setCatalogNodeId(rs.getObject("catalog_node_id") != null ? rs.getLong("catalog_node_id") : null);
            return t;
        }, pageParams.toArray());

        return new PageResult(content, totalElements, totalPages, page, size);
    }

    // ─── GetById（含版本历史）─────────────────────────────

    /** 获取任务详情（含版本历史 + 引用它的 ONLINE 工作流名单，供前端禁用下线按钮）。 */
    public Optional<TaskDetail> getById(Long id) {
        return taskDefRepository.findById(id).map(task -> {
            List<TaskDefVersion> versions = taskDefVersionRepository.findByTaskIdOrderByVersionNoDesc(id);
            List<String> refWorkflows = jdbcTemplate.query(
                    "SELECT DISTINCT wd.name FROM workflow_node wn "
                            + "JOIN workflow_def wd ON wd.id = wn.workflow_id "
                            + "WHERE wn.task_id = ? AND wn.deleted = 0 AND wd.deleted = 0 AND wd.status = 'ONLINE'",
                    (rs, n) -> rs.getString(1), id);
            return new TaskDetail(task, versions, refWorkflows);
        });
    }

    // ─── Update（仅 DRAFT 可改）──────────────────────────

    /**
     * 更新任务（DRAFT 或 ONLINE 均可，支持多次发布）。
     *
     * <p>ONLINE 任务编辑只改 {@code task_def}（草稿态），置 {@code hasDraftChange=1}，**不触动已发布版本快照**
     * （{@code task_def_version}）——正式调度/手动 NORMAL 运行仍跑已发布版本；TEST 试跑用草稿内容。
     * 改动经 {@link #publish} 才生成新版本上线（再发布）。
     */
    public TaskDef update(Long id, TaskDef patch) {
        TaskDef task = taskDefRepository.findById(id)
                .orElseThrow(() -> new BizException("task.not_found", id));
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

    /** 软删除任务（仅 DRAFT 且未被工作流关联可删）。 */
    public void softDelete(Long id) {
        TaskDef task = taskDefRepository.findById(id)
                .orElseThrow(() -> new BizException("task.not_found", id));
        if (!"DRAFT".equals(task.getStatus())) {
            throw new BizException("task.not_draft");
        }
        List<WorkflowNode> nodes = workflowNodeRepository.findByTaskIdAndDeleted(id, 0);
        if (!nodes.isEmpty()) {
            throw new BizException("task.associated_with_workflow", nodes.size());
        }
        task.setDeleted(1);
        task.setUpdatedAt(LocalDateTime.now());
        taskDefRepository.save(task);
    }

    // ─── 版本快照内核（状态中立，供 publish / push 复用，D1）─

    /**
     * 状态中立的建快照内核：创建 TaskDefVersion 行 + 推进 currentVersionNo。
     * 不改 status、不做无草稿变更守卫。push（US2）仅建快照不晋级 ONLINE；
     * publish() 复用本方法后再追加 ONLINE 晋级 + 引用闸门。
     *
     * @return 新版本号
     */
    public Integer writeTaskVersionSnapshot(TaskDef task, Long publishedBy, String remark) {
        LocalDateTime now = LocalDateTime.now();
        int newVersion = (task.getCurrentVersionNo() != null ? task.getCurrentVersionNo() : 0) + 1;

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
        ver.setRemark(remark != null ? remark : "快照 v" + newVersion);
        ver.setPublishedBy(publishedBy);
        ver.setPublishedAt(now);
        ver.setCreatedAt(now);
        taskDefVersionRepository.save(ver);

        task.setCurrentVersionNo(newVersion);
        task.setUpdatedAt(now);
        taskDefRepository.save(task);

        return newVersion;
    }

    // ─── Publish（DRAFT → ONLINE + 版本快照）─────────────

    /** 发布上线：DRAFT → ONLINE，生成版本快照。复用状态中立建快照内核 + 晋级 ONLINE + 无变更守卫。 */
    public TaskDef publish(Long id, String remark) {
        TaskDef task = taskDefRepository.findById(id)
                .orElseThrow(() -> new BizException("task.not_found", id));
        if ("ONLINE".equals(task.getStatus()) && (task.getHasDraftChange() == null || task.getHasDraftChange() == 0)) {
            throw new BizException("task.no_draft_changes");
        }

        writeTaskVersionSnapshot(task, null, remark);

        task.setStatus("ONLINE");
        task.setHasDraftChange(0);
        return taskDefRepository.save(task);
    }

    // ─── Offline（ONLINE → DRAFT）────────────────────────

    /** 下线：ONLINE → DRAFT。被任一 ONLINE 工作流引用的任务禁止下线（对称「ONLINE 工作流禁删」）。 */
    public TaskDef offline(Long id) {
        TaskDef task = taskDefRepository.findById(id)
                .orElseThrow(() -> new BizException("task.not_found", id));
        if (!"ONLINE".equals(task.getStatus())) {
            throw new BizException("task.already_offline");
        }
        // 引用完整性（ops-center-publish-boundary）：生产任务流脚下跑着的任务不能被单独抽走。
        List<String> refWorkflows = jdbcTemplate.query(
                "SELECT DISTINCT wd.name FROM workflow_node wn "
                        + "JOIN workflow_def wd ON wd.id = wn.workflow_id "
                        + "WHERE wn.task_id = ? AND wn.deleted = 0 AND wd.deleted = 0 AND wd.status = 'ONLINE'",
                (rs, n) -> rs.getString(1), id);
        if (!refWorkflows.isEmpty()) {
            throw new BizException("task.referenced_by_online_workflow", String.join(", ", refWorkflows));
        }
        task.setStatus("DRAFT");
        task.setUpdatedAt(LocalDateTime.now());
        return taskDefRepository.save(task);
    }

    // ─── Rollback（恢复历史版本为草稿）────────────────────

    /**
     * 回滚到指定历史版本：把该版本快照内容写回 {@code task_def}，置 {@code hasDraftChange=1}。
     * 不改变 {@code currentVersionNo} 和 {@code status}——用户需手动再发布。
     */
    public TaskDef rollback(Long taskId, Integer versionNo) {
        TaskDef task = taskDefRepository.findById(taskId)
                .orElseThrow(() -> new BizException("task.not_found", taskId));
        TaskDefVersion ver = taskDefVersionRepository.findByTaskIdAndVersionNo(taskId, versionNo)
                .orElseThrow(() -> new BizException("task.version_not_found", taskId, versionNo).withHttpStatus(404));
        task.setName(ver.getName());
        task.setType(ver.getType());
        task.setContent(ver.getContent());
        task.setDatasourceId(ver.getDatasourceId());
        task.setTargetDatasourceId(ver.getTargetDatasourceId());
        task.setParamsJson(ver.getParamsJson());
        task.setTimeoutSec(ver.getTimeoutSec());
        task.setRetryMax(ver.getRetryMax());
        task.setPriority(ver.getPriority());
        task.setDescription(ver.getDescription());
        task.setHasDraftChange(1);
        task.setUpdatedAt(LocalDateTime.now());
        return taskDefRepository.save(task);
    }

    // ─── 兼容旧方法（MCP create_task 调用）───────────────

    /** 创建任务定义（status=ONLINE）、版本快照 v1，并 mock 推进一条 SUCCESS 实例（无 io 声明）。 */
    public TaskCreation createAndOnline(String name, String type, String content, String cron) {
        return createAndOnline(name, type, content, cron, null, null, null, null);
    }

    /**
     * 创建即上线，并落设计态血缘（table-lineage）。
     *
     * <p>血缘三来源 A×B 交叉校验：{@code agentReads/agentWrites} 为 Agent 显式声明（AGENT），
     * 同时用 Calcite 解析 {@code content}（SQL_PARSED）。两者一致 → CONFIRMED；仅声明未证实 → CONFLICT；
     * 解析失败 → UNVERIFIED；仅解析未声明 → SQL_PARSED/CONFIRMED。reads 关联 {@code datasourceId}，
     * writes 关联 {@code targetDatasourceId}。血缘写入失败不影响建任务（容错降级）。
     */
    public TaskCreation createAndOnline(String name, String type, String content, String cron,
                                        Long datasourceId, Long targetDatasourceId,
                                        List<String> agentReads, List<String> agentWrites) {
        LocalDateTime now = LocalDateTime.now();

        TaskDef task = new TaskDef();
        task.setTenantId(1L);
        task.setProjectId(1L);
        task.setName(name);
        task.setType(type);
        task.setContent(content);
        task.setDatasourceId(datasourceId);
        task.setTargetDatasourceId(targetDatasourceId);
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

        recordLineage(saved.getId(), type, content, datasourceId, targetDatasourceId,
                agentReads, agentWrites);

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

    // ─── 设计态血缘记录（A×B 交叉校验）─────────────────────

    /** 解析 content + 合并 Agent 声明，落 task_table_io。容错：失败不影响建任务。 */
    private void recordLineage(Long taskDefId, String type, String content,
                              Long datasourceId, Long targetDatasourceId,
                              List<String> agentReads, List<String> agentWrites) {
        try {
            SqlTableExtractor.Result parsed = "SQL".equalsIgnoreCase(type)
                    ? sqlTableExtractor.extract(content)
                    : new SqlTableExtractor.Result(false, java.util.Set.of(), java.util.Set.of());

            List<LineageGraphService.EdgeInput> edges = new ArrayList<>();
            edges.addAll(buildEdges(LineageGraphService.READ, agentReads, parsed.reads(),
                    parsed.parsed(), datasourceId));
            edges.addAll(buildEdges(LineageGraphService.WRITE, agentWrites, parsed.writes(),
                    parsed.parsed(), targetDatasourceId));
            if (!edges.isEmpty()) {
                lineageGraphService.recordDesignTimeIo(1L, 1L, taskDefId, 1, edges);
            }
        } catch (Exception e) {
            // 血缘是增强，绝不阻断建任务主链路
        }
    }

    /**
     * 合并某方向的 Agent 声明与 SQL 解析结果，按 A×B 判定来源与可信度：
     * 两者皆有→AGENT/CONFIRMED；仅声明且解析成功→AGENT/CONFLICT；仅声明且解析失败→AGENT/UNVERIFIED；
     * 仅解析→SQL_PARSED/CONFIRMED。表名比对大小写不敏感，呈现保留声明优先的原始拼写。
     */
    private List<LineageGraphService.EdgeInput> buildEdges(String direction, List<String> agent,
                                                           java.util.Set<String> parsedSet,
                                                           boolean parsed, Long datasourceId) {
        // 规范化映射：lower → 原始拼写（声明优先）
        java.util.Map<String, String> canonical = new java.util.LinkedHashMap<>();
        java.util.Set<String> agentLower = new java.util.LinkedHashSet<>();
        if (agent != null) {
            for (String a : agent) {
                if (a == null || a.isBlank()) continue;
                String t = a.trim();
                canonical.put(t.toLowerCase(), t);
                agentLower.add(t.toLowerCase());
            }
        }
        java.util.Set<String> parsedLower = new java.util.LinkedHashSet<>();
        for (String p : parsedSet) {
            String low = p.toLowerCase();
            parsedLower.add(low);
            canonical.putIfAbsent(low, p);
        }

        List<LineageGraphService.EdgeInput> out = new ArrayList<>();
        for (String low : canonical.keySet()) {
            boolean inA = agentLower.contains(low);
            boolean inP = parsedLower.contains(low);
            String source;
            String confidence;
            if (inA && inP) { source = "AGENT"; confidence = "CONFIRMED"; }
            else if (inA && parsed) { source = "AGENT"; confidence = "CONFLICT"; }
            else if (inA) { source = "AGENT"; confidence = "UNVERIFIED"; }
            else { source = "SQL_PARSED"; confidence = "CONFIRMED"; }
            out.add(new LineageGraphService.EdgeInput(
                    datasourceId, canonical.get(low), null, direction, source, confidence));
        }
        return out;
    }
}
