package com.dataweave.master.application;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.domain.WorkflowDefVersion;
import com.dataweave.master.domain.WorkflowDefVersionRepository;
import com.dataweave.master.domain.WorkflowEdge;
import com.dataweave.master.domain.WorkflowEdgeRepository;
import com.dataweave.master.domain.WorkflowNode;
import com.dataweave.master.domain.WorkflowNodeRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
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

    private static final String EDGE_KEY_SEP = "";

    private final WorkflowDefRepository workflowDefRepository;
    private final WorkflowDefVersionRepository workflowDefVersionRepository;
    private final WorkflowNodeRepository nodeRepository;
    private final WorkflowEdgeRepository edgeRepository;
    private final TaskDefRepository taskDefRepository;
    private final WorkflowGraphValidator graphValidator;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WorkflowService(WorkflowDefRepository workflowDefRepository,
                           WorkflowDefVersionRepository workflowDefVersionRepository,
                           WorkflowNodeRepository nodeRepository,
                           WorkflowEdgeRepository edgeRepository,
                           TaskDefRepository taskDefRepository,
                           WorkflowGraphValidator graphValidator,
                           JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper) {
        this.workflowDefRepository = workflowDefRepository;
        this.workflowDefVersionRepository = workflowDefVersionRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.taskDefRepository = taskDefRepository;
        this.graphValidator = graphValidator;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ─── Records（接口契约）────────────────────────────────

    /** 分页结果。 */
    public record PageResult(List<WorkflowDef> content, long totalElements, int totalPages, int page, int size) {}

    /** DAG 节点 DTO（以 node_key 为稳定标识，不暴露自增主键给前端编辑态）。 */
    public record DagNodeDto(String nodeKey, String nodeType, Long taskId, String name, Integer posX, Integer posY) {}

    /** DAG 边 DTO（端点用 node_key 引用）。 */
    public record DagEdgeDto(String fromNodeKey, String toNodeKey) {}

    /** 读图视图：含乐观锁 version 与草稿标志，供前端保存时回传。 */
    public record DagView(Long workflowId, Long version, Integer hasDraftChange, String status,
                          List<DagNodeDto> nodes, List<DagEdgeDto> edges) {}

    /** 整图保存载荷：version 为客户端读到的乐观锁版本（可空=不校验）。 */
    public record DagPayload(Long version, List<DagNodeDto> nodes, List<DagEdgeDto> edges) {}

    // ─── 快照结构（发布冻结，序列化进 dag_snapshot_json）───

    private record SnapshotNode(String nodeKey, String nodeType, Long taskId, Integer taskVersionNo,
                                String name, Integer posX, Integer posY) {}
    private record SnapshotEdge(String fromNodeKey, String toNodeKey) {}
    private record Snapshot(List<SnapshotNode> nodes, List<SnapshotEdge> edges) {}

    // ─── Create（草稿）─────────────────────────────────────

    /** 创建工作流定义（status=DRAFT）。 */
    public WorkflowDef create(WorkflowDef wf) {
        LocalDateTime now = LocalDateTime.now();
        wf.setTenantId(1L);
        wf.setProjectId(1L);
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
        StringBuilder where = new StringBuilder("WHERE deleted = 0");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND name LIKE ?");
            params.add("%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status);
        }
        if (uncategorized) {
            where.append(" AND catalog_node_id IS NULL");
        } else if (catalogNodeId != null) {
            where.append(" AND catalog_node_id = ?");
            params.add(catalogNodeId);
        }
        if (tagId != null) {
            where.append(" AND id IN (SELECT entity_id FROM entity_tag WHERE tag_id = ? AND entity_type = 'WORKFLOW')");
            params.add(tagId);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_def " + where, Long.class, params.toArray());
        long totalElements = total != null ? total : 0;
        int totalPages = (int) Math.ceil((double) totalElements / size);

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add(page * size);
        List<WorkflowDef> content = jdbcTemplate.query(
                "SELECT * FROM workflow_def " + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
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

    public Optional<WorkflowDef> getById(Long id) {
        return workflowDefRepository.findById(id).filter(w -> w.getDeleted() == null || w.getDeleted() == 0);
    }

    // ─── Update（编辑调度配置）─────────────────────────────

    /** 编辑工作流（调度配置等）；置 has_draft_change=1。 */
    public WorkflowDef update(Long id, WorkflowDef patch) {
        WorkflowDef wf = requireWorkflow(id);
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
        wf.setUpdatedAt(LocalDateTime.now());
        return workflowDefRepository.save(wf);
    }

    // ─── SoftDelete / Offline ──────────────────────────────

    public void softDelete(Long id) {
        WorkflowDef wf = requireWorkflow(id);
        wf.setDeleted(1);
        wf.setUpdatedAt(LocalDateTime.now());
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
            nodeDtos.add(new DagNodeDto(n.getNodeKey(),
                    n.getNodeType() != null ? n.getNodeType() : "TASK",
                    n.getTaskId(), n.getName(), n.getPosX(), n.getPosY()));
        }
        List<DagEdgeDto> edgeDtos = new ArrayList<>();
        for (WorkflowEdge e : edgeRepository.findByWorkflowIdAndDeleted(id, 0)) {
            String from = idToKey.get(e.getFromNodeId());
            String to = idToKey.get(e.getToNodeId());
            if (from != null && to != null) {
                edgeDtos.add(new DagEdgeDto(from, to));
            }
        }
        return new DagView(wf.getId(), wf.getVersion(), wf.getHasDraftChange(), wf.getStatus(), nodeDtos, edgeDtos);
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
            if (!existingEdgeByPair.containsKey(pair)) {
                WorkflowEdge edge = new WorkflowEdge();
                edge.setTenantId(wf.getTenantId());
                edge.setProjectId(wf.getProjectId());
                edge.setWorkflowId(id);
                edge.setFromNodeId(fromId);
                edge.setToNodeId(toId);
                edge.setCreatedAt(now);
                edge.setUpdatedAt(now);
                edge.setDeleted(0);
                edge.setVersion(0L);
                edgeRepository.save(edge);
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

    // ─── Publish（无环校验 + 冻结快照 + 版本自增）──────────

    @Transactional
    public WorkflowDef publish(Long id, String remark) {
        WorkflowDef wf = requireWorkflow(id);
        if ("ONLINE".equals(wf.getStatus()) && (wf.getHasDraftChange() == null || wf.getHasDraftChange() == 0)) {
            throw new BizException("workflow.publish.no_change").withHttpStatus(409);
        }
        List<WorkflowNode> nodes = nodeRepository.findByWorkflowIdAndDeleted(id, 0);
        if (nodes.isEmpty()) {
            throw new BizException("workflow.publish.empty").withHttpStatus(409);
        }
        for (WorkflowNode n : nodes) {
            boolean virtual = "VIRTUAL".equals(n.getNodeType());
            if (!virtual && n.getTaskId() == null) {
                throw new BizException("workflow.node.task_unbound", n.getNodeKey());
            }
        }
        // 无环校验（复用既有校验器，只看未删边）
        graphValidator.validateWorkflowDagAcyclic(id);

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
        ver.setDagSnapshotJson(buildSnapshotJson(id, nodes));
        ver.setRemark(remark != null ? remark : "发布 v" + newVersion);
        ver.setPublishedAt(now);
        ver.setCreatedAt(now);
        workflowDefVersionRepository.save(ver);

        wf.setStatus("ONLINE");
        wf.setCurrentVersionNo(newVersion);
        wf.setHasDraftChange(0);
        wf.setUpdatedAt(now);
        wf.setVersion((wf.getVersion() != null ? wf.getVersion() : 0L) + 1);
        return workflowDefRepository.save(wf);
    }

    /** 冻结整张 DAG 为 JSON（nodes 含各 TASK 节点 current_version_no + edges）。 */
    private String buildSnapshotJson(Long id, List<WorkflowNode> nodes) {
        Map<Long, String> idToKey = new HashMap<>();
        List<SnapshotNode> snapNodes = new ArrayList<>();
        for (WorkflowNode n : nodes) {
            idToKey.put(n.getId(), n.getNodeKey());
            Integer taskVersionNo = null;
            if (n.getTaskId() != null) {
                taskVersionNo = taskDefRepository.findById(n.getTaskId())
                        .map(TaskDef::getCurrentVersionNo).orElse(null);
            }
            snapNodes.add(new SnapshotNode(n.getNodeKey(),
                    n.getNodeType() != null ? n.getNodeType() : "TASK",
                    n.getTaskId(), taskVersionNo, n.getName(), n.getPosX(), n.getPosY()));
        }
        List<SnapshotEdge> snapEdges = new ArrayList<>();
        for (WorkflowEdge e : edgeRepository.findByWorkflowIdAndDeleted(id, 0)) {
            String from = idToKey.get(e.getFromNodeId());
            String to = idToKey.get(e.getToNodeId());
            if (from != null && to != null) {
                snapEdges.add(new SnapshotEdge(from, to));
            }
        }
        return objectMapper.writeValueAsString(new Snapshot(snapNodes, snapEdges));
    }

    // ─── helpers ───────────────────────────────────────────

    private WorkflowDef requireWorkflow(Long id) {
        return workflowDefRepository.findById(id)
                .filter(w -> w.getDeleted() == null || w.getDeleted() == 0)
                .orElseThrow(() -> new BizException("workflow.not_found", id).withHttpStatus(404));
    }
}
