package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.WorkflowDagSnapshot;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.domain.TaskDefVersion;
import com.dataweave.master.domain.TaskDefVersionRepository;
import com.dataweave.master.domain.WorkflowDefVersion;
import com.dataweave.master.domain.WorkflowDefVersionRepository;
import com.dataweave.master.domain.WorkflowEdge;
import com.dataweave.master.domain.WorkflowEdgeRepository;
import com.dataweave.master.domain.WorkflowDependency;
import com.dataweave.master.domain.WorkflowDependencyRepository;
import com.dataweave.master.domain.WorkflowNode;
import com.dataweave.master.domain.WorkflowNodeRepository;
import com.dataweave.master.i18n.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工作流编排服务：工作流定义 CRUD + DAG 整图读写 + 发布版本冻结（workflow-canvas）。
 *
 * <p>编辑态写 {@code workflow_node}/{@code workflow_edge}（草稿），发布时复用
 * {@link WorkflowGraphValidator} 做无环校验、冻结 {@code dag_snapshot_json} 并自增版本号。
 * 节点分 {@code TASK}（绑 task_def）与 {@code VIRTUAL}（zero-load 锚点，不绑任务）。
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private static final String EDGE_KEY_SEP = "";

    private final WorkflowDefRepository workflowDefRepository;
    private final WorkflowDefVersionRepository workflowDefVersionRepository;
    private final WorkflowNodeRepository nodeRepository;
    private final WorkflowEdgeRepository edgeRepository;
    private final WorkflowDependencyRepository dependencyRepository;
    private final TaskDefRepository taskDefRepository;
    private final TaskDefVersionRepository taskDefVersionRepository;
    private final WorkflowGraphValidator graphValidator;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WorkflowService(WorkflowDefRepository workflowDefRepository,
                           WorkflowDefVersionRepository workflowDefVersionRepository,
                           WorkflowNodeRepository nodeRepository,
                           WorkflowEdgeRepository edgeRepository,
                           WorkflowDependencyRepository dependencyRepository,
                           TaskDefRepository taskDefRepository,
                           TaskDefVersionRepository taskDefVersionRepository,
                           WorkflowGraphValidator graphValidator,
                           JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper) {
        this.workflowDefRepository = workflowDefRepository;
        this.workflowDefVersionRepository = workflowDefVersionRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.dependencyRepository = dependencyRepository;
        this.taskDefRepository = taskDefRepository;
        this.taskDefVersionRepository = taskDefVersionRepository;
        this.graphValidator = graphValidator;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ─── Records（接口契约）────────────────────────────────

    /** 分页结果。 */
    public record PageResult(List<WorkflowDef> content, long totalElements, int totalPages, int page, int size) {}

    /**
     * DAG 节点 DTO（以 node_key 为稳定标识，不暴露自增主键给前端编辑态）。
     * {@code taskStatus} 为该 TASK 节点引用任务的发布态（ONLINE/DRAFT）——供画布标「未发布」节点
     * （ops-center-publish-boundary）；VIRTUAL 节点或任务缺失时为 null。
     */
    public record DagNodeDto(String nodeKey, String nodeType, Long taskId, Integer taskVersionNo,
                             String name, Integer posX, Integer posY, String taskStatus) {
        /** 6 参便捷构造（taskVersionNo/taskStatus 默认 null）：兼容不关心发布态与版本的旧调用与测试。 */
        public DagNodeDto(String nodeKey, String nodeType, Long taskId, String name,
                          Integer posX, Integer posY) {
            this(nodeKey, nodeType, taskId, null, name, posX, posY, null);
        }
    }

    /** DAG 边 DTO（端点用 node_key 引用）。 */
    public record DagEdgeDto(String fromNodeKey, String toNodeKey, String strength) {}

    /** 读图视图：含乐观锁 version 与草稿标志，供前端保存时回传。 */
    public record DagView(Long workflowId, Long version, Integer hasDraftChange, String status,
                          List<DagNodeDto> nodes, List<DagEdgeDto> edges) {}

    /** 整图保存载荷：version 为客户端读到的乐观锁版本（可空=不校验）。 */
    public record DagPayload(Long version, List<DagNodeDto> nodes, List<DagEdgeDto> edges) {}

    /**
     * DAG 节点任务版本详情（003-node-detail-panel）。
     * 从已发布 DAG 快照中根据 nodeKey 定位节点，再按 taskId + taskVersionNo 查询
     * {@code task_def_version} 表返回发布时冻结的任务配置。
     * {@code hasCode} 由任务类型元数据推导；{@code deleted} 标记关联任务是否已被删除。
     */
    public record NodeTaskDetail(String nodeKey, Long taskId, String taskName, String taskType,
                                  Integer versionNo, String content, String paramsJson,
                                  Long datasourceId, Long targetDatasourceId,
                                  Integer timeoutSec, Integer retryMax,
                                  String publishedAt, boolean hasCode, boolean deleted) {}

    /** 工作流详情（含版本历史），与 TaskService.TaskDetail 对称。 */
    public record WorkflowDetail(WorkflowDef workflow, List<WorkflowDefVersion> versions) {}

    /** 单节点漂移：快照钉死版本 {@code pinned} 落后于任务最新发布版 {@code latest}。 */
    public record DriftNode(String nodeKey, Integer pinned, Integer latest) {}

    /**
     * 工作流漂移结果（workflow-version-binding D3，读侧计算不落库）。
     * {@code drifted} = 任务版本漂移（{@code driftedNodes} 非空）或 DAG 草稿漂移（{@code dagDraft}）。
     */
    public record DriftResult(boolean drifted, boolean dagDraft, List<DriftNode> driftedNodes) {}

    /**
     * 跨周期依赖 DTO（自依赖=dependWorkflowId 同 workflow + dependNodeId 同 node；earliestBizDate 空=不启用）。
     * <p>nodeKey/dependNodeKey 是前端画布节点标识（与 {@link DagNodeDto#nodeKey()} 一致），内部转 workflow_node.id 存储；
     * list 回填 key 供前端渲染，create 接 key 解析 id（nodeId/dependNodeId 兼容旧调用，优先级高于 key）。
     */
    public record DependencyDto(Long id, Long nodeId, Long dependWorkflowId, Long dependNodeId,
                                String nodeKey, String dependNodeKey,
                                String dateOffset, String earliestBizDate, Integer enabled) {}

    // ─── 快照结构（发布冻结，序列化进 dag_snapshot_json）：见公共 WorkflowDagSnapshot（domain）───

    // ─── Create（草稿）─────────────────────────────────────

    /** 创建工作流定义（status=DRAFT）。 */
    public WorkflowDef create(WorkflowDef wf) {
        LocalDateTime now = LocalDateTime.now();
        if (wf.getTenantId() == null) wf.setTenantId(1L);
        if (wf.getProjectId() == null) wf.setProjectId(1L);
        wf.setStatus("DRAFT");
        wf.setCurrentVersionNo(0);
        wf.setHasDraftChange(1);
        if (wf.getScheduleType() == null) wf.setScheduleType("MANUAL");
        if (wf.getPriority() == null) wf.setPriority(5);
        if (wf.getPreemptible() == null) wf.setPreemptible(0);
        wf.setCreatedAt(now);
        wf.setUpdatedAt(now);
        wf.setDeleted(0);
        wf.setVersion(0L);
        return workflowDefRepository.save(wf);
    }

    // ─── Search（分页搜索）─────────────────────────────────

    /** 分页搜索工作流定义（兼容旧签名，无类目/标签过滤）。 */
    public PageResult search(String keyword, String status, int page, int size) {
        return search(keyword, status, null, false, null, page, size);
    }

    /**
     * 分页搜索工作流定义，支持类目/标签过滤。
     *
     * @param catalogNodeId 归属文件夹过滤（null 且 uncategorized=false 时不过滤）
     * @param uncategorized true 仅返回未归类（catalog_node_id IS NULL）
     * @param tagId         标签过滤（null 不过滤）
     */
    public PageResult search(String keyword, String status, Long catalogNodeId,
                             boolean uncategorized, Long tagId, int page, int size) {
        StringBuilder where = new StringBuilder("WHERE w.deleted = 0");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND w.name LIKE ?");
            params.add("%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND w.status = ?");
            params.add(status);
        }
        if (uncategorized) {
            where.append(" AND w.catalog_node_id IS NULL");
        } else if (catalogNodeId != null) {
            where.append(" AND w.catalog_node_id = ?");
            params.add(catalogNodeId);
        }
        if (tagId != null) {
            where.append(" AND w.id IN (SELECT entity_id FROM entity_tag WHERE tag_id = ? AND entity_type = 'WORKFLOW')");
            params.add(tagId);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_def w " + where, Long.class, params.toArray());
        long totalElements = total != null ? total : 0;
        int totalPages = (int) Math.ceil((double) totalElements / size);

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add(page * size);
        List<WorkflowDef> content = jdbcTemplate.query(
                "SELECT w.* FROM workflow_def w LEFT JOIN catalog_node cn ON w.catalog_node_id = cn.id AND cn.deleted = 0 " + where + " ORDER BY cn.path ASC, w.created_at DESC LIMIT ? OFFSET ?",
                (rs, n) -> mapWorkflow(rs), pageParams.toArray());

        return new PageResult(content, totalElements, totalPages, page, size);
    }

    private WorkflowDef mapWorkflow(java.sql.ResultSet rs) throws java.sql.SQLException {
        WorkflowDef w = new WorkflowDef();
        w.setId(rs.getLong("id"));
        w.setTenantId(rs.getLong("tenant_id"));
        w.setProjectId(rs.getLong("project_id"));
        w.setName(rs.getString("name"));
        w.setDescription(rs.getString("description"));
        w.setScheduleType(rs.getString("schedule_type"));
        w.setCron(rs.getString("cron"));
        w.setScheduleStart(rs.getTimestamp("schedule_start") != null ? rs.getTimestamp("schedule_start").toLocalDateTime() : null);
        w.setScheduleEnd(rs.getTimestamp("schedule_end") != null ? rs.getTimestamp("schedule_end").toLocalDateTime() : null);
        w.setStatus(rs.getString("status"));
        w.setCurrentVersionNo(rs.getObject("current_version_no") != null ? rs.getInt("current_version_no") : null);
        w.setHasDraftChange(rs.getObject("has_draft_change") != null ? rs.getInt("has_draft_change") : null);
        w.setLastFireTime(rs.getTimestamp("last_fire_time") != null ? rs.getTimestamp("last_fire_time").toLocalDateTime() : null);
        w.setPriority(rs.getObject("priority") != null ? rs.getInt("priority") : null);
        w.setPreemptible(rs.getObject("preemptible") != null ? rs.getInt("preemptible") : null);
        w.setTimeoutSec(rs.getObject("timeout_sec") != null ? rs.getInt("timeout_sec") : null);
        w.setCreatedBy(rs.getObject("created_by") != null ? rs.getLong("created_by") : null);
        w.setUpdatedBy(rs.getObject("updated_by") != null ? rs.getLong("updated_by") : null);
        w.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null);
        w.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
        w.setDeleted(rs.getObject("deleted") != null ? rs.getInt("deleted") : null);
        w.setVersion(rs.getObject("version") != null ? rs.getLong("version") : null);
        w.setCatalogNodeId(rs.getObject("catalog_node_id") != null ? rs.getLong("catalog_node_id") : null);
        return w;
    }

    // ─── GetById ───────────────────────────────────────────

    public Optional<WorkflowDetail> getById(Long id) {
        return workflowDefRepository.findById(id)
                .filter(w -> w.getDeleted() == null || w.getDeleted() == 0)
                .map(wf -> {
                    List<WorkflowDefVersion> versions =
                            workflowDefVersionRepository.findByWorkflowIdOrderByVersionNoDesc(id);
                    return new WorkflowDetail(wf, versions);
                });
    }

    /**
     * 计算工作流漂移（workflow-version-binding D3）：读侧计算，不落库，每次读时算保证永远准确。
     *
     * <p>「需要重新晋级」当且仅当满足任一：(a) 任务版本漂移——当前已发布快照中某节点钉死的
     * {@code taskVersionNo} 落后于该 task 的最新 {@code current_version_no}；(b) DAG 草稿漂移——
     * {@code has_draft_change=1}（live DAG/属性改了未发布）。无快照（未发布）则仅看 (b)。
     */
    public DriftResult computeDrift(Long workflowId) {
        WorkflowDef wf = requireWorkflow(workflowId);
        boolean dagDraft = wf.getHasDraftChange() != null && wf.getHasDraftChange() == 1;
        List<DriftNode> driftedNodes = new ArrayList<>();
        Integer ver = wf.getCurrentVersionNo();
        if (ver != null && ver > 0) {
            workflowDefVersionRepository.findByWorkflowIdAndVersionNo(workflowId, ver)
                    .map(WorkflowDefVersion::getDagSnapshotJson)
                    .filter(s -> s != null && !s.isBlank())
                    .ifPresent(json -> {
                        try {
                            WorkflowDagSnapshot snap = objectMapper.readValue(json, WorkflowDagSnapshot.class);
                            // 一次性批量查各 task 的最新发布版（按快照节点 taskId 去重）
                            Set<Long> taskIds = new HashSet<>();
                            for (WorkflowDagSnapshot.Node n : snap.nodes()) {
                                if (n.taskId() != null) taskIds.add(n.taskId());
                            }
                            Map<Long, Integer> latestByTask = new HashMap<>();
                            taskDefRepository.findAllById(taskIds)
                                    .forEach(t -> latestByTask.put(t.getId(), t.getCurrentVersionNo()));
                            for (WorkflowDagSnapshot.Node n : snap.nodes()) {
                                if (n.taskId() == null) continue;
                                Integer pinned = n.taskVersionNo();
                                Integer latest = latestByTask.get(n.taskId());
                                if (latest != null && latest > 0 && (pinned == null || latest > pinned)) {
                                    driftedNodes.add(new DriftNode(n.nodeKey(), pinned, latest));
                                }
                            }
                        } catch (Exception e) {
                            log.warn("computeDrift 解析 dag_snapshot_json 失败（workflowId={}，versionNo={}）：{}",
                                    workflowId, ver, e.getMessage());
                        }
                    });
        }
        boolean drifted = dagDraft || !driftedNodes.isEmpty();
        return new DriftResult(drifted, dagDraft, driftedNodes);
    }

    // ─── Update（编辑调度配置）─────────────────────────────

    /** 编辑工作流（调度配置等）；置 has_draft_change=1。 */
    public WorkflowDef update(Long id, WorkflowDef patch) {
        WorkflowDef wf = requireWorkflow(id);
        applyConfigPatch(wf, patch);
        wf.setUpdatedAt(LocalDateTime.now());
        return workflowDefRepository.save(wf);
    }

    /** 字段级 patch（非空才覆盖），不落库、不改 DAG version；供 {@link #update} 与 {@link #saveDraft} 复用。 */
    private void applyConfigPatch(WorkflowDef wf, WorkflowDef patch) {
        if (patch.getName() != null) wf.setName(patch.getName());
        if (patch.getDescription() != null) wf.setDescription(patch.getDescription());
        if (patch.getScheduleType() != null) wf.setScheduleType(patch.getScheduleType());
        if (patch.getCron() != null) wf.setCron(patch.getCron());
        if (patch.getScheduleStart() != null) wf.setScheduleStart(patch.getScheduleStart());
        if (patch.getScheduleEnd() != null) wf.setScheduleEnd(patch.getScheduleEnd());
        if (patch.getPriority() != null) wf.setPriority(patch.getPriority());
        if (patch.getPreemptible() != null) wf.setPreemptible(patch.getPreemptible());
        if (patch.getTimeoutSec() != null) wf.setTimeoutSec(patch.getTimeoutSec());
        wf.setHasDraftChange(1);
    }

    /**
     * 草稿整体保存（save-draft-atomic）：配置 patch + DAG 整图保存在**同一事务**内。
     * DAG 乐观锁冲突（409）或节点/边校验失败时整体回滚——避免「配置存了、DAG 没存」的不一致。
     * config 可空（仅存图）；返回最新 {@link DagView}。
     */
    @Transactional
    public DagView saveDraft(Long id, WorkflowDef config, DagPayload payload) {
        if (config != null) {
            WorkflowDef wf = requireWorkflow(id);
            applyConfigPatch(wf, config);
            wf.setUpdatedAt(LocalDateTime.now());
            workflowDefRepository.save(wf);
        }
        // saveDag 末尾会 +1 DAG version 并再存 wf；与上面的配置 patch 同处一个事务。
        return saveDag(id, payload);
    }

    // ─── SoftDelete / Offline ──────────────────────────────

    @Transactional
    public void softDelete(Long id) {
        WorkflowDef wf = requireWorkflow(id);
        // 与 task 对称：仅 DRAFT 可删（ONLINE 须先下线），避免误删在产工作流。
        if (!"DRAFT".equals(wf.getStatus())) {
            throw new BizException("workflow.not_draft").withHttpStatus(409);
        }
        // 级联软删节点 + 边：解除对 task 的关联引用，否则被本工作流引用的任务删除时
        // 会被 task.associated_with_workflow 卡住（节点残留 deleted=0）。
        LocalDateTime now = LocalDateTime.now();
        for (WorkflowNode n : nodeRepository.findByWorkflowIdAndDeleted(id, 0)) {
            n.setDeleted(1);
            n.setUpdatedAt(now);
            nodeRepository.save(n);
        }
        for (WorkflowEdge e : edgeRepository.findByWorkflowIdAndDeleted(id, 0)) {
            e.setDeleted(1);
            e.setUpdatedAt(now);
            edgeRepository.save(e);
        }
        wf.setDeleted(1);
        wf.setUpdatedAt(now);
        workflowDefRepository.save(wf);
    }

    /** 下线：ONLINE → DRAFT。 */
    public WorkflowDef offline(Long id) {
        WorkflowDef wf = requireWorkflow(id);
        if (!"ONLINE".equals(wf.getStatus())) {
            throw new BizException("workflow.not_online").withHttpStatus(409);
        }
        wf.setStatus("DRAFT");
        wf.setUpdatedAt(LocalDateTime.now());
        return workflowDefRepository.save(wf);
    }

    // ─── Read DAG ──────────────────────────────────────────

    public DagView readDag(Long id) {
        WorkflowDef wf = requireWorkflow(id);
        List<WorkflowNode> nodes = nodeRepository.findByWorkflowIdAndDeleted(id, 0);
        Map<Long, String> idToKey = new HashMap<>();
        List<DagNodeDto> nodeDtos = new ArrayList<>();
        for (WorkflowNode n : nodes) {
            idToKey.put(n.getId(), n.getNodeKey());
            // 引用任务发布态（供画布标「未发布」节点）：VIRTUAL 或任务缺失为 null。
            String taskStatus = n.getTaskId() == null ? null
                    : taskDefRepository.findById(n.getTaskId()).map(TaskDef::getStatus).orElse(null);
            nodeDtos.add(new DagNodeDto(n.getNodeKey(),
                    n.getNodeType() != null ? n.getNodeType() : "TASK",
                    n.getTaskId(), null, n.getName(), n.getPosX(), n.getPosY(), taskStatus));
        }
        List<DagEdgeDto> edgeDtos = new ArrayList<>();
        for (WorkflowEdge e : edgeRepository.findByWorkflowIdAndDeleted(id, 0)) {
            String from = idToKey.get(e.getFromNodeId());
            String to = idToKey.get(e.getToNodeId());
            if (from != null && to != null) {
                edgeDtos.add(new DagEdgeDto(from, to, e.getStrength() != null ? e.getStrength() : "STRONG"));
            }
        }
        return new DagView(wf.getId(), wf.getVersion(), wf.getHasDraftChange(), wf.getStatus(), nodeDtos, edgeDtos);
    }

    // ─── Read Published DAG ──────────────────────────────────

    /**
     * 读取已发布版本的 DAG 快照（运维查看线上拓扑），从 {@code workflow_def_version.dag_snapshot_json} 反序列化。
     * 仅 ONLINE 且有发布版本时可用；DRAFT 或未发布抛异常。
     */
    public DagView readPublishedDag(Long id) {
        WorkflowDef wf = requireWorkflow(id);
        if (!"ONLINE".equals(wf.getStatus()) || wf.getCurrentVersionNo() == null || wf.getCurrentVersionNo() == 0) {
            throw new BizException("workflow.not_online_or_unpublished").withHttpStatus(404);
        }
        WorkflowDefVersion ver = workflowDefVersionRepository
                .findByWorkflowIdAndVersionNo(id, wf.getCurrentVersionNo())
                .orElseThrow(() -> new BizException("workflow.not_online_or_unpublished").withHttpStatus(404));
        WorkflowDagSnapshot snap;
        try {
            snap = objectMapper.readValue(ver.getDagSnapshotJson(), WorkflowDagSnapshot.class);
        } catch (Exception e) {
            log.error("Failed to deserialize dagSnapshotJson for workflow {}", id, e);
            throw new BizException("workflow.dag_snapshot_corrupt").withHttpStatus(500);
        }
        // 构建 nodeKey → 当前 WorkflowNode 映射，用于快照字段为空时的 fallback
        Map<String, WorkflowNode> liveNodeByKey = new HashMap<>();
        for (WorkflowNode ln : nodeRepository.findByWorkflowIdAndDeleted(id, 0)) {
            liveNodeByKey.put(ln.getNodeKey(), ln);
        }

        List<DagNodeDto> nodeDtos = new ArrayList<>();
        if (snap.nodes() != null) {
            for (WorkflowDagSnapshot.Node n : snap.nodes()) {
                WorkflowNode live = liveNodeByKey.get(n.nodeKey());
                // 快照缺失显示字段时，fallback 到当前 workflow_node 表数据
                String name = n.name() != null ? n.name() : (live != null ? live.getName() : null);
                Integer posX = n.posX() != null ? n.posX() : (live != null ? live.getPosX() : null);
                Integer posY = n.posY() != null ? n.posY() : (live != null ? live.getPosY() : null);
                String taskStatus = n.taskId() == null ? null
                        : taskDefRepository.findById(n.taskId()).map(TaskDef::getStatus).orElse(null);
                nodeDtos.add(new DagNodeDto(n.nodeKey(), n.nodeType(), n.taskId(),
                        n.taskVersionNo(), name, posX, posY, taskStatus));
            }
        }
        List<DagEdgeDto> edgeDtos = new ArrayList<>();
        if (snap.edges() != null) {
            for (WorkflowDagSnapshot.Edge e : snap.edges()) {
                edgeDtos.add(new DagEdgeDto(e.fromNodeKey(), e.toNodeKey(),
                        e.strength() != null ? e.strength() : "STRONG"));
            }
        }
        return new DagView(wf.getId(), Long.valueOf(ver.getVersionNo()), wf.getHasDraftChange(),
                wf.getStatus(), nodeDtos, edgeDtos);
    }

    /**
     * 获取 DAG 节点关联任务的发布版本详情（003-node-detail-panel）。
     * 从已发布快照中根据 nodeKey 定位节点，读取 taskId + taskVersionNo，
     * 再从 {@code task_def_version} 查询发布时冻结的任务配置。
     */
    public NodeTaskDetail getNodeDetail(Long workflowId, String nodeKey) {
        WorkflowDef wf = requireWorkflow(workflowId);
        if (!"ONLINE".equals(wf.getStatus()) || wf.getCurrentVersionNo() == null || wf.getCurrentVersionNo() == 0) {
            throw new BizException("workflow.not_online_or_unpublished").withHttpStatus(404);
        }
        WorkflowDefVersion ver = workflowDefVersionRepository
                .findByWorkflowIdAndVersionNo(workflowId, wf.getCurrentVersionNo())
                .orElseThrow(() -> new BizException("workflow.not_online_or_unpublished").withHttpStatus(404));
        WorkflowDagSnapshot snap;
        try {
            snap = objectMapper.readValue(ver.getDagSnapshotJson(), WorkflowDagSnapshot.class);
        } catch (Exception e) {
            log.error("Failed to deserialize dagSnapshotJson for workflow {}", workflowId, e);
            throw new BizException("workflow.dag_snapshot_corrupt").withHttpStatus(500);
        }
        // 在快照中定位节点
        WorkflowDagSnapshot.Node foundNode = null;
        if (snap.nodes() != null) {
            for (WorkflowDagSnapshot.Node n : snap.nodes()) {
                if (n.nodeKey().equals(nodeKey)) {
                    foundNode = n;
                    break;
                }
            }
        }
        if (foundNode == null) {
            throw new BizException("workflow.node_not_found_in_snapshot").withHttpStatus(404);
        }
        if (!"TASK".equals(foundNode.nodeType())) {
            throw new BizException("node_detail.virtual_no_task").withHttpStatus(400);
        }
        if (foundNode.taskId() == null) {
            throw new BizException("node_detail.virtual_no_task").withHttpStatus(400);
        }

        // 查询发布时冻结的任务版本
        TaskDefVersion taskVer = taskDefVersionRepository
                .findByTaskIdAndVersionNo(foundNode.taskId(), foundNode.taskVersionNo())
                .orElse(null);

        boolean deleted = taskVer == null;
        String taskName = foundNode.name();
        String taskType = null;
        String content = null;
        String paramsJson = null;
        Long datasourceId = null;
        Long targetDatasourceId = null;
        Integer timeoutSec = null;
        Integer retryMax = null;
        String publishedAt = null;

        if (taskVer != null) {
            taskName = taskVer.getName() != null ? taskVer.getName() : taskName;
            taskType = taskVer.getType();
            content = taskVer.getContent();
            paramsJson = taskVer.getParamsJson();
            datasourceId = taskVer.getDatasourceId();
            targetDatasourceId = taskVer.getTargetDatasourceId();
            timeoutSec = taskVer.getTimeoutSec();
            retryMax = taskVer.getRetryMax();
            publishedAt = taskVer.getPublishedAt() != null ? taskVer.getPublishedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null;
        }

        boolean hasCode = taskType != null && !"DATA_SYNC".equalsIgnoreCase(taskType);

        return new NodeTaskDetail(nodeKey, foundNode.taskId(), taskName,
                taskType != null ? taskType : "UNKNOWN",
                foundNode.taskVersionNo(), content, paramsJson,
                datasourceId, targetDatasourceId,
                timeoutSec != null ? timeoutSec : 3600,
                retryMax != null ? retryMax : 3,
                publishedAt, hasCode, deleted);
    }

    // ─── Save DAG（整图对账保存）──────────────────────────

    @Transactional
    public DagView saveDag(Long id, DagPayload payload) {
        WorkflowDef wf = requireWorkflow(id);
        // 乐观锁：客户端带回读到的 version，与库不一致即冲突。
        if (payload.version() != null && !payload.version().equals(wf.getVersion())) {
            throw new BizException("workflow.stale_version").withHttpStatus(409);
        }
        List<DagNodeDto> nodes = payload.nodes() != null ? payload.nodes() : List.of();
        List<DagEdgeDto> edges = payload.edges() != null ? payload.edges() : List.of();

        // 校验：TASK 节点必须绑定任务；VIRTUAL 允许空。node_key 必须唯一非空。
        Set<String> seenKeys = new HashSet<>();
        for (DagNodeDto dto : nodes) {
            if (dto.nodeKey() == null || dto.nodeKey().isBlank()) {
                throw new BizException("workflow.node.key_missing");
            }
            if (!seenKeys.add(dto.nodeKey())) {
                throw new BizException("workflow.node.key_duplicate", dto.nodeKey());
            }
            String type = dto.nodeType() != null ? dto.nodeType() : "TASK";
            if ("TASK".equals(type) && dto.taskId() == null) {
                throw new BizException("workflow.node.task_unbound", dto.nodeKey());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        List<WorkflowNode> existingNodes = nodeRepository.findByWorkflowIdAndDeleted(id, 0);
        Map<String, WorkflowNode> existingByKey = new HashMap<>();
        for (WorkflowNode n : existingNodes) {
            existingByKey.put(n.getNodeKey(), n);
        }

        // upsert 节点，记录 nodeKey → id
        Map<String, Long> keyToId = new HashMap<>();
        for (DagNodeDto dto : nodes) {
            WorkflowNode node = existingByKey.get(dto.nodeKey());
            if (node == null) {
                node = new WorkflowNode();
                node.setTenantId(wf.getTenantId());
                node.setProjectId(wf.getProjectId());
                node.setWorkflowId(id);
                node.setNodeKey(dto.nodeKey());
                node.setCreatedAt(now);
                node.setDeleted(0);
                node.setVersion(0L);
            }
            node.setNodeType(dto.nodeType() != null ? dto.nodeType() : "TASK");
            node.setTaskId(dto.taskId());
            node.setName(dto.name());
            node.setPosX(dto.posX());
            node.setPosY(dto.posY());
            node.setUpdatedAt(now);
            WorkflowNode saved = nodeRepository.save(node);
            keyToId.put(dto.nodeKey(), saved.getId());
        }
        // 软删被省略的节点
        for (WorkflowNode n : existingNodes) {
            if (!seenKeys.contains(n.getNodeKey())) {
                n.setDeleted(1);
                n.setUpdatedAt(now);
                nodeRepository.save(n);
            }
        }

        // 边对账：用全量节点 id↔key 解析既有边端点
        Map<Long, String> idToKey = new HashMap<>();
        for (WorkflowNode n : nodeRepository.findByWorkflowId(id)) {
            idToKey.put(n.getId(), n.getNodeKey());
        }
        List<WorkflowEdge> existingEdges = edgeRepository.findByWorkflowIdAndDeleted(id, 0);
        Map<String, WorkflowEdge> existingEdgeByPair = new HashMap<>();
        for (WorkflowEdge e : existingEdges) {
            String from = idToKey.get(e.getFromNodeId());
            String to = idToKey.get(e.getToNodeId());
            if (from != null && to != null) {
                existingEdgeByPair.put(from + EDGE_KEY_SEP + to, e);
            }
        }
        Set<String> incomingPairs = new HashSet<>();
        for (DagEdgeDto dto : edges) {
            Long fromId = keyToId.get(dto.fromNodeKey());
            Long toId = keyToId.get(dto.toNodeKey());
            if (fromId == null || toId == null) {
                throw new BizException("workflow.edge.node_not_found", dto.fromNodeKey(), dto.toNodeKey());
            }
            String pair = dto.fromNodeKey() + EDGE_KEY_SEP + dto.toNodeKey();
            incomingPairs.add(pair);
            String strength = "WEAK".equals(dto.strength()) ? "WEAK" : "STRONG";
            WorkflowEdge existing = existingEdgeByPair.get(pair);
            if (existing == null) {
                WorkflowEdge edge = new WorkflowEdge();
                edge.setTenantId(wf.getTenantId());
                edge.setProjectId(wf.getProjectId());
                edge.setWorkflowId(id);
                edge.setFromNodeId(fromId);
                edge.setToNodeId(toId);
                edge.setStrength(strength);
                edge.setCreatedAt(now);
                edge.setUpdatedAt(now);
                edge.setDeleted(0);
                edge.setVersion(0L);
                edgeRepository.save(edge);
            } else if (!strength.equals(existing.getStrength())) {
                // 既有边强度变化（STRONG↔WEAK）也要落库
                existing.setStrength(strength);
                existing.setUpdatedAt(now);
                edgeRepository.save(existing);
            }
        }
        // 软删被省略的边
        for (WorkflowEdge e : existingEdges) {
            String from = idToKey.get(e.getFromNodeId());
            String to = idToKey.get(e.getToNodeId());
            String pair = from + EDGE_KEY_SEP + to;
            if (!incomingPairs.contains(pair)) {
                e.setDeleted(1);
                e.setUpdatedAt(now);
                edgeRepository.save(e);
            }
        }

        wf.setHasDraftChange(1);
        wf.setUpdatedAt(now);
        wf.setVersion((wf.getVersion() != null ? wf.getVersion() : 0L) + 1);
        workflowDefRepository.save(wf);

        return readDag(id);
    }

    // ─── 版本快照内核（状态中立，供 publish / push 复用，D1）─

    /**
     * 状态中立的建快照内核：基于当前 live 节点/边创建 WorkflowDefVersion 并推进 currentVersionNo。
     * 不晋级 ONLINE、不做空 DAG/引用完整性闸门。push（US2）仅建快照不晋级；
     * publish() 复用本方法后再追加 ONLINE 晋级 + 各守卫。
     *
     * @return 新版本号
     */
    public Integer writeWorkflowVersionSnapshot(Long workflowId, String remark) {
        WorkflowDef wf = requireWorkflow(workflowId);
        List<WorkflowNode> nodes = nodeRepository.findByWorkflowIdAndDeleted(workflowId, 0);

        LocalDateTime now = LocalDateTime.now();
        int newVersion = (wf.getCurrentVersionNo() != null ? wf.getCurrentVersionNo() : 0) + 1;

        WorkflowDefVersion ver = new WorkflowDefVersion();
        ver.setTenantId(wf.getTenantId());
        ver.setProjectId(wf.getProjectId());
        ver.setWorkflowId(wf.getId());
        ver.setVersionNo(newVersion);
        ver.setName(wf.getName());
        ver.setDescription(wf.getDescription());
        ver.setScheduleType(wf.getScheduleType());
        ver.setCron(wf.getCron());
        ver.setDagSnapshotJson(buildSnapshotJson(workflowId, nodes));
        ver.setRemark(remark != null ? remark : "快照 v" + newVersion);
        ver.setPublishedAt(now);
        ver.setCreatedAt(now);
        workflowDefVersionRepository.save(ver);

        wf.setCurrentVersionNo(newVersion);
        wf.setUpdatedAt(now);
        workflowDefRepository.save(wf);

        return newVersion;
    }

    // ─── Publish（无环校验 + 冻结快照 + 版本自增）──────────

    @Transactional
    public WorkflowDef publish(Long id, String remark) {
        WorkflowDef wf = requireWorkflow(id);
        // 「无变更」= 无 DAG 草稿改动 且 无任务版本漂移。重新晋级（workflow-version-binding）采纳任务新版时
        // hasDraftChange=0 但快照钉死版落后于任务最新发布版（drifted），应放行——否则无法采纳漂移。
        if ("ONLINE".equals(wf.getStatus())
                && (wf.getHasDraftChange() == null || wf.getHasDraftChange() == 0)
                && !computeDrift(id).drifted()) {
            throw new BizException("workflow.publish.no_change").withHttpStatus(409);
        }
        List<WorkflowNode> nodes = nodeRepository.findByWorkflowIdAndDeleted(id, 0);
        if (nodes.isEmpty()) {
            throw new BizException("workflow.publish.empty").withHttpStatus(409);
        }
        List<String> notOnlineNodes = new ArrayList<>();
        for (WorkflowNode n : nodes) {
            boolean virtual = "VIRTUAL".equals(n.getNodeType());
            if (!virtual && n.getTaskId() == null) {
                throw new BizException("workflow.node.task_unbound", n.getNodeKey());
            }
            // 引用完整性（ops-center-publish-boundary）：发布即晋级生产，所有 TASK 节点引用的任务必须已发布(ONLINE)。
            if (!virtual) {
                boolean online = taskDefRepository.findById(n.getTaskId())
                        .map(t -> "ONLINE".equals(t.getStatus())).orElse(false);
                if (!online) {
                    notOnlineNodes.add(n.getName() != null ? n.getName() : n.getNodeKey());
                }
            }
        }
        if (!notOnlineNodes.isEmpty()) {
            throw new BizException("workflow.node_task_not_online", String.join(", ", notOnlineNodes))
                    .withHttpStatus(409);
        }
        // 无环校验（复用既有校验器，只看未删边）
        graphValidator.validateWorkflowDagAcyclic(id);

        // writeWorkflowVersionSnapshot 已在库中把 current_version_no 推进到 newVersionNo（经其自身载入的实例）。
        // 必须把新版本号同步回外层 stale 实例，否则下面的 save(wf) 会用旧值（0）覆盖刚写入的版本号。
        Integer newVersionNo = writeWorkflowVersionSnapshot(id, remark);

        wf.setCurrentVersionNo(newVersionNo);
        wf.setStatus("ONLINE");
        wf.setHasDraftChange(0);
        wf.setUpdatedAt(LocalDateTime.now());
        wf.setVersion((wf.getVersion() != null ? wf.getVersion() : 0L) + 1);
        return workflowDefRepository.save(wf);
    }

    /** 冻结整张 DAG 为 JSON（nodes 含各 TASK 节点 current_version_no + edges）。 */
    private String buildSnapshotJson(Long id, List<WorkflowNode> nodes) {
        Map<Long, String> idToKey = new HashMap<>();
        List<WorkflowDagSnapshot.Node> snapNodes = new ArrayList<>();
        for (WorkflowNode n : nodes) {
            idToKey.put(n.getId(), n.getNodeKey());
            Integer taskVersionNo = null;
            if (n.getTaskId() != null) {
                taskVersionNo = taskDefRepository.findById(n.getTaskId())
                        .map(TaskDef::getCurrentVersionNo).orElse(null);
            }
            snapNodes.add(new WorkflowDagSnapshot.Node(n.getNodeKey(),
                    n.getNodeType() != null ? n.getNodeType() : "TASK",
                    n.getTaskId(), taskVersionNo, n.getName(), n.getPosX(), n.getPosY()));
        }
        List<WorkflowDagSnapshot.Edge> snapEdges = new ArrayList<>();
        for (WorkflowEdge e : edgeRepository.findByWorkflowIdAndDeleted(id, 0)) {
            String from = idToKey.get(e.getFromNodeId());
            String to = idToKey.get(e.getToNodeId());
            if (from != null && to != null) {
                snapEdges.add(new WorkflowDagSnapshot.Edge(from, to, e.getStrength() != null ? e.getStrength() : "STRONG"));
            }
        }
        return objectMapper.writeValueAsString(new WorkflowDagSnapshot(snapNodes, snapEdges));
    }

    // ─── 跨周期依赖 CRUD（cross-cycle-dependency）──────────

    /** 列出工作流的启用跨周期依赖。 */
    public List<DependencyDto> listDependencies(Long workflowId) {
        requireWorkflow(workflowId);
        return dependencyRepository.findByWorkflowId(workflowId).stream()
                .filter(d -> d.getDeleted() == null || d.getDeleted() == 0)
                .map(this::toDependencyDto).toList();
    }

    /**
     * 新建跨周期依赖。自依赖（dependWorkflowId=workflowId 且 dependNodeId=nodeId）合法直接放行；
     * 非自指做全局跨流环检测（成环拒绝）。dateOffset 缺省 CURRENT_DAY，earliestBizDate 空=不启用。
     */
    public DependencyDto createDependency(Long workflowId, DependencyDto dto) {
        WorkflowDef wf = requireWorkflow(workflowId);
        graphValidator.validateDependencyAcyclic(workflowId, dto.dependWorkflowId());
        LocalDateTime now = LocalDateTime.now();
        // 前端传 nodeKey（画布标识），内部解析为 workflow_node.id；自依赖=dependWorkflowId 同本流
        Long nodeId = dto.nodeId() != null ? dto.nodeId() : resolveNodeId(workflowId, dto.nodeKey());
        if (nodeId == null) {
            throw new BizException("workflow.dependency.node_not_found", dto.nodeKey());
        }
        Long dependWfId = dto.dependWorkflowId() != null ? dto.dependWorkflowId() : workflowId;
        Long dependNodeId = dto.dependNodeId() != null ? dto.dependNodeId() : resolveNodeId(dependWfId, dto.dependNodeKey());
        WorkflowDependency d = new WorkflowDependency();
        d.setTenantId(wf.getTenantId());
        d.setProjectId(wf.getProjectId());
        d.setWorkflowId(workflowId);
        d.setNodeId(nodeId);
        d.setDependWorkflowId(dependWfId);
        d.setDependNodeId(dependNodeId);
        d.setDateOffset(dto.dateOffset() != null && !dto.dateOffset().isBlank() ? dto.dateOffset() : "CURRENT_DAY");
        d.setEarliestBizDate(dto.earliestBizDate());
        d.setEnabled(dto.enabled() != null ? dto.enabled() : 1);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        d.setDeleted(0);
        d.setVersion(0L);
        return toDependencyDto(dependencyRepository.save(d));
    }

    /** 删除跨周期依赖（软删）。 */
    public void deleteDependency(Long workflowId, Long depId) {
        WorkflowDependency d = dependencyRepository.findById(depId)
                .filter(x -> x.getWorkflowId().equals(workflowId))
                .orElseThrow(() -> new BizException("workflow.dependency.not_found", depId).withHttpStatus(404));
        d.setDeleted(1);
        d.setUpdatedAt(LocalDateTime.now());
        dependencyRepository.save(d);
    }

    private DependencyDto toDependencyDto(WorkflowDependency d) {
        return new DependencyDto(d.getId(), d.getNodeId(), d.getDependWorkflowId(), d.getDependNodeId(),
                nodeKeyOf(d.getNodeId()), nodeKeyOf(d.getDependNodeId()),
                d.getDateOffset(), d.getEarliestBizDate(), d.getEnabled());
    }

    // ─── helpers ───────────────────────────────────────────

    /** nodeKey → workflow_node.id（按 workflow + key 查）；找不到返回 null。 */
    private Long resolveNodeId(Long workflowId, String nodeKey) {
        if (workflowId == null || nodeKey == null) return null;
        return nodeRepository.findByWorkflowIdAndDeleted(workflowId, 0).stream()
                .filter(n -> nodeKey.equals(n.getNodeKey()))
                .map(WorkflowNode::getId).findFirst().orElse(null);
    }

    /** workflow_node.id → nodeKey；找不到返回 null。 */
    private String nodeKeyOf(Long nodeId) {
        if (nodeId == null) return null;
        return nodeRepository.findById(nodeId).map(WorkflowNode::getNodeKey).orElse(null);
    }

    private WorkflowDef requireWorkflow(Long id) {
        return workflowDefRepository.findById(id)
                .filter(w -> w.getDeleted() == null || w.getDeleted() == 0)
                .orElseThrow(() -> new BizException("workflow.not_found", id).withHttpStatus(404));
    }

    // ─── Rollback（恢复历史版本为草稿）────────────────────

    /**
     * 回滚到指定历史版本：把该版本 DAG 快照写回 nodes/edges，配置字段写回 {@code workflow_def}，
     * 置 {@code hasDraftChange=1}。不改变 {@code currentVersionNo} 和 {@code status}。
     */
    public WorkflowDef rollback(Long workflowId, Integer versionNo) {
        WorkflowDef wf = requireWorkflow(workflowId);
        WorkflowDefVersion ver = workflowDefVersionRepository.findByWorkflowIdAndVersionNo(workflowId, versionNo)
                .orElseThrow(() -> new BizException("workflow.version_not_found", workflowId, versionNo).withHttpStatus(404));

        // 解析快照 → DagPayload 复用 saveDag 对账逻辑
        DagPayload payload;
        try {
            WorkflowDagSnapshot snap = objectMapper.readValue(ver.getDagSnapshotJson(), WorkflowDagSnapshot.class);
            // 快照节点为已发布版本回滚源，引用任务在发布时即 ONLINE，taskStatus 记 ONLINE（VIRTUAL 为 null）。
            List<DagNodeDto> nodes = snap.nodes().stream()
                    .map(sn -> new DagNodeDto(sn.nodeKey(), sn.nodeType(), sn.taskId(),
                            sn.taskVersionNo(), sn.name(), sn.posX(), sn.posY(),
                            sn.taskId() == null ? null : "ONLINE"))
                    .toList();
            List<DagEdgeDto> edges = snap.edges().stream()
                    .map(se -> new DagEdgeDto(se.fromNodeKey(), se.toNodeKey(), se.strength()))
                    .toList();
            payload = new DagPayload(wf.getVersion(), nodes, edges);
        } catch (Exception e) {
            throw new BizException("workflow.dag_parse_failed", versionNo);
        }
        saveDag(workflowId, payload);

        // 写回配置字段
        wf.setName(ver.getName());
        wf.setDescription(ver.getDescription());
        wf.setScheduleType(ver.getScheduleType());
        wf.setCron(ver.getCron());
        wf.setHasDraftChange(1);
        wf.setUpdatedAt(LocalDateTime.now());
        return workflowDefRepository.save(wf);
    }
}
