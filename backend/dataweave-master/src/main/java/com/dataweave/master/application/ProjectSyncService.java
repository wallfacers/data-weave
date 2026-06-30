package com.dataweave.master.application;

import com.dataweave.master.domain.*;
import com.dataweave.master.filecontract.FileContract;
import com.dataweave.master.filecontract.ProjectExport;
import com.dataweave.master.filecontract.ProjectFileBundle;
import com.dataweave.master.filecontract.ProjectImport;
import com.dataweave.master.filecontract.naming.EntityNaming;
import com.dataweave.master.application.lineage.LineageEdgeAssembler;
import com.dataweave.master.domain.lineage.LineageStore;
import com.dataweave.master.i18n.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 项目同步编排：pull（装配→序列化）/ push（反序列化→校验→落库→快照）/ diff（只读对账）。
 * 子特性 C 的核心 application service。
 */
@Service
public class ProjectSyncService {

    private static final Logger log = LoggerFactory.getLogger(ProjectSyncService.class);

    private final ProjectRepository projectRepository;
    private final CatalogNodeRepository catalogNodeRepository;
    private final TaskDefRepository taskDefRepository;
    private final WorkflowDefRepository workflowDefRepository;
    private final TagRepository tagRepository;
    private final EntityTagRepository entityTagRepository;
    private final WorkflowNodeRepository workflowNodeRepository;
    private final WorkflowEdgeRepository workflowEdgeRepository;
    private final DatasourceRepository datasourceRepository;
    private final TaskService taskService;
    private final WorkflowService workflowService;
    private final LineageStore lineageStore;
    private final LineageEdgeAssembler lineageEdgeAssembler;
    private final SqlColumnLineageExtractor sqlColumnLineageExtractor;

    public ProjectSyncService(ProjectRepository projectRepository,
                              CatalogNodeRepository catalogNodeRepository,
                              TaskDefRepository taskDefRepository,
                              WorkflowDefRepository workflowDefRepository,
                              TagRepository tagRepository,
                              EntityTagRepository entityTagRepository,
                              WorkflowNodeRepository workflowNodeRepository,
                              WorkflowEdgeRepository workflowEdgeRepository,
                              DatasourceRepository datasourceRepository,
                              TaskService taskService,
                              WorkflowService workflowService,
                              LineageStore lineageStore,
                              LineageEdgeAssembler lineageEdgeAssembler,
                              SqlColumnLineageExtractor sqlColumnLineageExtractor) {
        this.projectRepository = projectRepository;
        this.catalogNodeRepository = catalogNodeRepository;
        this.taskDefRepository = taskDefRepository;
        this.workflowDefRepository = workflowDefRepository;
        this.tagRepository = tagRepository;
        this.entityTagRepository = entityTagRepository;
        this.workflowNodeRepository = workflowNodeRepository;
        this.workflowEdgeRepository = workflowEdgeRepository;
        this.datasourceRepository = datasourceRepository;
        this.taskService = taskService;
        this.workflowService = workflowService;
        this.lineageStore = lineageStore;
        this.lineageEdgeAssembler = lineageEdgeAssembler;
        this.sqlColumnLineageExtractor = sqlColumnLineageExtractor;
    }

    // ═══════════════════════════════════════════════════════════════
    // 隔离守卫（FR-012）
    // ═══════════════════════════════════════════════════════════════

    /** 租户隔离：校验 project.tenantId == 当前租户，否则抛 access_denied。 */
    Project requireOwnedProject(Long projectId, Long tenantId) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new BizException("project.not_found", projectId).withHttpStatus(404));
        if (tenantId != null && !tenantId.equals(p.getTenantId())) {
            throw new BizException("project.access_denied").withHttpStatus(403);
        }
        return p;
    }

    // ═══════════════════════════════════════════════════════════════
    // 基线令牌（D4）
    // ═══════════════════════════════════════════════════════════════

    /** 计算项目当前定义的不透明修订令牌。 */
    String computeBaseline(Long projectId) {
        List<String> rows = new ArrayList<>();

        // tasks: (id, version) 稳定排序
        List<TaskDef> tasks = taskDefRepository.findByProjectId(projectId);
        tasks.sort(Comparator.comparing(TaskDef::getId));
        for (TaskDef t : tasks) {
            rows.add("task:" + t.getId() + ":" + (t.getVersion() != null ? t.getVersion() : 0));
        }

        // workflows: (id, version)
        List<WorkflowDef> workflows = workflowDefRepository.findByProjectId(projectId);
        workflows.sort(Comparator.comparing(WorkflowDef::getId));
        for (WorkflowDef w : workflows) {
            rows.add("workflow:" + w.getId() + ":" + (w.getVersion() != null ? w.getVersion() : 0));
        }

        // catalogs: (id, version)
        List<CatalogNode> catalogs = catalogNodeRepository.findByProjectIdAndDeleted(projectId, 0);
        catalogs.sort(Comparator.comparing(CatalogNode::getId));
        for (CatalogNode c : catalogs) {
            rows.add("catalog:" + c.getId() + ":" + (c.getVersion() != null ? c.getVersion() : 0));
        }

        // tags: (id, updatedAt)
        List<Tag> tags = tagRepository.findByProjectIdOrderByNameAsc(projectId);
        tags.sort(Comparator.comparing(Tag::getId));
        for (Tag t : tags) {
            rows.add("tag:" + t.getId() + ":" + (t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : "0"));
        }

        String joined = String.join("\n", rows);
        return sha256Hex16(joined);
    }

    private static String sha256Hex16(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) { // first 16 hex chars = first 8 bytes
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 身份对账引擎（D2 纯计算部分，供 US2 写、US3 读复用）
    // ═══════════════════════════════════════════════════════════════

    /** 三态对账结果（纯数据，不写库）。 */
    record ReconResult(
            // Catalogs
            List<CatalogNode> insertCatalogs,
            List<CatalogNode> updateCatalogs,
            List<CatalogNode> deleteCatalogs,
            // Tasks
            List<TaskDef> insertTasks,
            List<TaskDef> updateTasks,
            List<TaskDef> deleteTasks,
            // Workflows
            List<WorkflowDef> insertWorkflows,
            List<WorkflowDef> updateWorkflows,
            List<WorkflowDef> deleteWorkflows,
            // Tags
            List<Tag> insertTags,
            List<Tag> updateTags,
            List<Tag> deleteTags,
            // ID 映射: 本地合成 id → 服务器真实 id
            Map<Long, Long> synTaskIdToRealId,
            Map<Long, Long> synWfIdToRealId,
            // Diff display
            List<ProjectSyncDtos.EntityRef> added,
            List<ProjectSyncDtos.EntityRef> modified,
            List<ProjectSyncDtos.EntityRef> removed
    ) {}

    /**
     * 身份对账：ProjectImport(本地) vs 服务器现状 → 三态 + diff 列表。
     * 只计算，不写库（供 push 写路径和 diff 只读路径复用）。
     */
    ReconResult reconcile(ProjectImport local, Long projectId) {
        // ── 服务器现状 ──
        List<CatalogNode> srvCatalogs = catalogNodeRepository.findByProjectIdAndDeleted(projectId, 0);
        List<TaskDef> srvTasks = new ArrayList<>();
        for (TaskDef t : taskDefRepository.findByProjectId(projectId)) {
            if (t.getDeleted() == null || t.getDeleted() == 0) srvTasks.add(t);
        }
        List<WorkflowDef> srvWorkflows = new ArrayList<>();
        for (WorkflowDef w : workflowDefRepository.findByProjectId(projectId)) {
            if (w.getDeleted() == null || w.getDeleted() == 0) srvWorkflows.add(w);
        }
        List<Tag> srvTags = tagRepository.findByProjectIdOrderByNameAsc(projectId);

        // ID 映射: 本地合成 id → 服务器真实 id
        Map<Long, Long> synTaskIdToRealId = new HashMap<>();
        Map<Long, Long> synWfIdToRealId = new HashMap<>();

        // ── 辅助映射 ──
        // 本地: 合成 catalogId → path
        Map<Long, String> localCatalogPath = new HashMap<>();
        for (CatalogNode c : local.catalogs()) {
            localCatalogPath.put(c.getId(), c.getPath());
        }
        // 本地: 合成 catalogId → catalog 实体
        Map<Long, CatalogNode> localCatalogById = new HashMap<>();
        for (CatalogNode c : local.catalogs()) {
            localCatalogById.put(c.getId(), c);
        }
        // 服务器: catalogId → path
        Map<Long, String> srvCatalogPath = new HashMap<>();
        for (CatalogNode c : srvCatalogs) {
            srvCatalogPath.put(c.getId(), c.getPath());
        }

        // task slug: 本地合成 taskId → slug
        Map<Long, String> localTaskSlug = local.taskSlugs();
        // workflow slug: 本地合成 wfId → slug
        Map<Long, String> localWfSlug = local.workflowSlugs();

        // ── Catalogs: 按 path 匹配 ──
        Map<String, CatalogNode> srvCatalogByPath = new HashMap<>();
        for (CatalogNode c : srvCatalogs) {
            srvCatalogByPath.put(c.getPath(), c);
        }
        Set<String> localCatalogPaths = new HashSet<>();
        List<CatalogNode> insertCatalogs = new ArrayList<>();
        List<CatalogNode> updateCatalogs = new ArrayList<>();
        List<ProjectSyncDtos.EntityRef> addedRefs = new ArrayList<>();
        List<ProjectSyncDtos.EntityRef> modifiedRefs = new ArrayList<>();

        for (CatalogNode localCat : local.catalogs()) {
            String path = localCat.getPath();
            localCatalogPaths.add(path);
            CatalogNode srvCat = srvCatalogByPath.get(path);
            if (srvCat == null) {
                insertCatalogs.add(localCat);
                addedRefs.add(new ProjectSyncDtos.EntityRef("CATALOG", path, localCat.getName()));
            } else if (!Objects.equals(localCat.getName(), srvCat.getName())) {
                // 回填真实 id
                localCat.setId(srvCat.getId());
                updateCatalogs.add(localCat);
                modifiedRefs.add(new ProjectSyncDtos.EntityRef("CATALOG", path, localCat.getName()));
            }
        }
        List<CatalogNode> deleteCatalogs = new ArrayList<>();
        List<ProjectSyncDtos.EntityRef> removedRefs = new ArrayList<>();
        for (CatalogNode srvCat : srvCatalogs) {
            if (!localCatalogPaths.contains(srvCat.getPath())) {
                deleteCatalogs.add(srvCat);
                removedRefs.add(new ProjectSyncDtos.EntityRef("CATALOG", srvCat.getPath(), srvCat.getName()));
            }
        }

        // ── Tags: 按 name 匹配 ──
        Map<String, Tag> srvTagByName = new HashMap<>();
        for (Tag t : srvTags) {
            srvTagByName.put(t.getName(), t);
        }
        Set<String> localTagNames = new HashSet<>();
        List<Tag> insertTags = new ArrayList<>();
        List<Tag> updateTags = new ArrayList<>();

        for (Tag localTag : local.tags()) {
            localTagNames.add(localTag.getName());
            Tag srvTag = srvTagByName.get(localTag.getName());
            if (srvTag == null) {
                insertTags.add(localTag);
                addedRefs.add(new ProjectSyncDtos.EntityRef("TAG", localTag.getName(), localTag.getName()));
            } else if (!Objects.equals(localTag.getColor(), srvTag.getColor())) {
                localTag.setId(srvTag.getId());
                updateTags.add(localTag);
                modifiedRefs.add(new ProjectSyncDtos.EntityRef("TAG", localTag.getName(), localTag.getName()));
            }
        }
        List<Tag> deleteTags = new ArrayList<>();
        for (Tag srvTag : srvTags) {
            if (!localTagNames.contains(srvTag.getName())) {
                deleteTags.add(srvTag);
                removedRefs.add(new ProjectSyncDtos.EntityRef("TAG", srvTag.getName(), srvTag.getName()));
            }
        }

        // ── Tasks: 按 (catalogPath, slug) 匹配 ──
        // 013: 服务端 slug 同样经 uniquify（同目录兄弟集），与导出同源，保证 INV-4
        record TaskKey(String catalogPath, String slug) {}

        // 服务端任务：按 catalogPath 分组，组内 uniquify 后建身份键
        Map<String, List<TaskDef>> srvTasksByPath = new LinkedHashMap<>();
        for (TaskDef t : srvTasks) {
            String catPath = t.getCatalogNodeId() != null ? srvCatalogPath.getOrDefault(t.getCatalogNodeId(), "") : "";
            srvTasksByPath.computeIfAbsent(catPath, k -> new ArrayList<>()).add(t);
        }
        Map<Long, String> srvTaskUniqueSlug = new LinkedHashMap<>(); // real id → unique slug
        Map<TaskKey, TaskDef> srvTaskByKey = new HashMap<>();
        for (var pathEntry : srvTasksByPath.entrySet()) {
            String catPath = pathEntry.getKey();
            List<Map.Entry<Long, String>> siblings = pathEntry.getValue().stream()
                    .map(t -> Map.entry(t.getId(), EntityNaming.effectiveSlug(t.getName())))
                    .toList();
            Map<Long, String> unique = EntityNaming.uniquify(siblings);
            srvTaskUniqueSlug.putAll(unique);
            for (TaskDef t : pathEntry.getValue()) {
                srvTaskByKey.put(new TaskKey(catPath, unique.get(t.getId())), t);
            }
        }
        Set<TaskKey> localTaskKeys = new HashSet<>();
        List<TaskDef> insertTasks = new ArrayList<>();
        List<TaskDef> updateTasks = new ArrayList<>();

        for (TaskDef localTask : local.tasks()) {
            String catPath = localTask.getCatalogNodeId() != null ? localCatalogPath.getOrDefault(localTask.getCatalogNodeId(), "") : "";
            String slug = localTaskSlug.getOrDefault(localTask.getId(), slugify(localTask.getName()));
            TaskKey key = new TaskKey(catPath, slug);
            localTaskKeys.add(key);
            TaskDef srvTask = srvTaskByKey.get(key);
            if (srvTask == null) {
                insertTasks.add(localTask);
                addedRefs.add(new ProjectSyncDtos.EntityRef("TASK", catPath + "/" + slug, localTask.getName()));
            } else {
                // 所有匹配(含未变更)都记录 合成→真实 id —— 供 node.taskId / entityTag 解析。
                // 不再 setId(保留合成 id),否则下游 node/edge/entityTag 无法按合成 id 解析。
                synTaskIdToRealId.put(localTask.getId(), srvTask.getId());
                if (taskContentDiffers(localTask, srvTask)) {
                    updateTasks.add(localTask);
                    modifiedRefs.add(new ProjectSyncDtos.EntityRef("TASK", catPath + "/" + slug, localTask.getName()));
                }
            }
        }
        List<TaskDef> deleteTasks = new ArrayList<>();
        for (TaskDef srvTask : srvTasks) {
            String catPath = srvTask.getCatalogNodeId() != null ? srvCatalogPath.getOrDefault(srvTask.getCatalogNodeId(), "") : "";
            String slug = srvTaskUniqueSlug.getOrDefault(srvTask.getId(), slugify(srvTask.getName()));
            if (!localTaskKeys.contains(new TaskKey(catPath, slug))) {
                deleteTasks.add(srvTask);
                removedRefs.add(new ProjectSyncDtos.EntityRef("TASK", catPath + "/" + slug, srvTask.getName()));
            }
        }

        // ── Workflows: 按 slug 匹配（013: 服务端同样经 uniquify）──
        Map<String, List<WorkflowDef>> srvWfsByPath = new LinkedHashMap<>();
        for (WorkflowDef w : srvWorkflows) {
            String catPath = w.getCatalogNodeId() != null ? srvCatalogPath.getOrDefault(w.getCatalogNodeId(), "") : "";
            srvWfsByPath.computeIfAbsent(catPath, k -> new ArrayList<>()).add(w);
        }
        Map<Long, String> srvWfUniqueSlug = new LinkedHashMap<>(); // real id → unique slug
        Map<String, WorkflowDef> srvWfBySlug = new HashMap<>();
        for (var pathEntry : srvWfsByPath.entrySet()) {
            List<Map.Entry<Long, String>> siblings = pathEntry.getValue().stream()
                    .map(w -> Map.entry(w.getId(), EntityNaming.effectiveSlug(w.getName())))
                    .toList();
            Map<Long, String> unique = EntityNaming.uniquify(siblings);
            srvWfUniqueSlug.putAll(unique);
            for (WorkflowDef w : pathEntry.getValue()) {
                srvWfBySlug.put(unique.get(w.getId()), w);
            }
        }
        Set<String> localWfSlugs = new HashSet<>();
        List<WorkflowDef> insertWorkflows = new ArrayList<>();
        List<WorkflowDef> updateWorkflows = new ArrayList<>();

        for (WorkflowDef localWf : local.workflows()) {
            String slug = localWfSlug.getOrDefault(localWf.getId(), slugify(localWf.getName()));
            localWfSlugs.add(slug);
            WorkflowDef srvWf = srvWfBySlug.get(slug);
            if (srvWf == null) {
                insertWorkflows.add(localWf);
                addedRefs.add(new ProjectSyncDtos.EntityRef("WORKFLOW", slug, localWf.getName()));
            } else {
                // 所有匹配都记录 合成→真实 —— DAG 重建按合成 wfId 解析,不再 setId。
                synWfIdToRealId.put(localWf.getId(), srvWf.getId());
                if (workflowContentDiffers(localWf, srvWf)) {
                    updateWorkflows.add(localWf);
                    modifiedRefs.add(new ProjectSyncDtos.EntityRef("WORKFLOW", slug, localWf.getName()));
                }
            }
        }
        List<WorkflowDef> deleteWorkflows = new ArrayList<>();
        for (WorkflowDef srvWf : srvWorkflows) {
            String slug = srvWfUniqueSlug.getOrDefault(srvWf.getId(), slugify(srvWf.getName()));
            if (!localWfSlugs.contains(slug)) {
                deleteWorkflows.add(srvWf);
                removedRefs.add(new ProjectSyncDtos.EntityRef("WORKFLOW", slug, srvWf.getName()));
            }
        }

        return new ReconResult(
                insertCatalogs, updateCatalogs, deleteCatalogs,
                insertTasks, updateTasks, deleteTasks,
                insertWorkflows, updateWorkflows, deleteWorkflows,
                insertTags, updateTags, deleteTags,
                synTaskIdToRealId, synWfIdToRealId,
                addedRefs, modifiedRefs, removedRefs);
    }

    /** Simple slug: delegates to {@link EntityNaming#effectiveSlug(String)} — the single source of truth (013). */
    static String slugify(String name) {
        return EntityNaming.effectiveSlug(name);
    }

    /**
     * Compute unique slugs for a set of entities, applying {@link EntityNaming#uniquify}
     * within each sibling group (entities sharing the same catalogNodeId / directory).
     * This guarantees INV-2 (same-directory uniqueness) and INV-4 (export == identity).
     *
     * @param nameById   list of (entityId, name) pairs
     * @param groupById  entityId → group key (catalogNodeId; null = root)
     * @return entityId → unique final slug
     */
    private static Map<Long, String> computeUniqueSlugs(
            List<Map.Entry<Long, String>> nameById,
            Map<Long, Long> groupById) {
        // Group entity ids by their group key (null catalogNodeId = root, mapped to 0L)
        Map<Long, List<Map.Entry<Long, String>>> groups = new LinkedHashMap<>();
        for (var entry : nameById) {
            Long id = entry.getKey();
            Long raw = groupById.get(id);
            Long groupKey = raw != null ? raw : 0L; // 0L = root (null catalogNodeId or absent)
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(entry);
        }
        Map<Long, String> result = new LinkedHashMap<>();
        for (var group : groups.values()) {
            List<Map.Entry<Long, String>> siblings = group.stream()
                    .map(e -> Map.entry(e.getKey(), EntityNaming.effectiveSlug(e.getValue())))
                    .toList();
            result.putAll(EntityNaming.uniquify(siblings));
        }
        return result;
    }

    /** Compare task fields that matter for identity/content change detection.
     *  Excludes datasourceId/targetDatasourceId (synthetic vs real) and catalogNodeId (resolved separately). */
    private boolean taskContentDiffers(TaskDef local, TaskDef server) {
        return !Objects.equals(local.getName(), server.getName())
                || !Objects.equals(local.getType(), server.getType())
                || !Objects.equals(local.getContent(), server.getContent())
                || !Objects.equals(local.getParamsJson(), server.getParamsJson())
                || !Objects.equals(local.getTimeoutSec(), server.getTimeoutSec())
                || !Objects.equals(local.getRetryMax(), server.getRetryMax())
                || !Objects.equals(local.getPriority(), server.getPriority())
                || !Objects.equals(local.getDescription(), server.getDescription());
    }

    /** Compare workflow config fields (excluding DAG - DAG compared separately). */
    private boolean workflowContentDiffers(WorkflowDef local, WorkflowDef server) {
        return !Objects.equals(local.getName(), server.getName())
                || !Objects.equals(local.getDescription(), server.getDescription())
                || !Objects.equals(local.getScheduleType(), server.getScheduleType())
                || !Objects.equals(local.getCron(), server.getCron());
    }

    /** 服务器侧某 workflow 的 DAG 稳定签名(nodeKey/type/taskId + edge by nodeKey)。 */
    private String dagSignature(Long realWfId) {
        List<WorkflowNode> nodes = workflowNodeRepository.findByWorkflowIdAndDeleted(realWfId, 0);
        Map<Long, String> idToKey = new HashMap<>();
        List<String> nodeParts = new ArrayList<>();
        for (WorkflowNode n : nodes) {
            idToKey.put(n.getId(), n.getNodeKey());
            nodeParts.add("n:" + n.getNodeKey() + ":" + n.getNodeType() + ":" + n.getTaskId());
        }
        Collections.sort(nodeParts);
        List<String> edgeParts = new ArrayList<>();
        for (WorkflowEdge e : workflowEdgeRepository.findByWorkflowIdAndDeleted(realWfId, 0)) {
            edgeParts.add("e:" + idToKey.get(e.getFromNodeId()) + "->" + idToKey.get(e.getToNodeId())
                    + ":" + e.getStrength());
        }
        Collections.sort(edgeParts);
        nodeParts.addAll(edgeParts);
        return String.join("|", nodeParts);
    }

    /** 本地(导入)某 workflow 期望的 DAG 签名,taskId 用 合成→真实 映射解析,与 {@link #dagSignature} 同格式。 */
    private String desiredDagSignature(Long synWfId, ProjectImport imported, Map<Long, Long> synTaskToReal) {
        Map<Long, String> synNodeKey = new HashMap<>();
        List<String> nodeParts = new ArrayList<>();
        for (WorkflowNode n : imported.workflowNodes()) {
            if (!n.getWorkflowId().equals(synWfId)) continue;
            synNodeKey.put(n.getId(), n.getNodeKey());
            Long realTask = n.getTaskId() != null ? synTaskToReal.get(n.getTaskId()) : null;
            nodeParts.add("n:" + n.getNodeKey() + ":" + n.getNodeType() + ":" + realTask);
        }
        Collections.sort(nodeParts);
        List<String> edgeParts = new ArrayList<>();
        for (WorkflowEdge e : imported.workflowEdges()) {
            if (!e.getWorkflowId().equals(synWfId)) continue;
            edgeParts.add("e:" + synNodeKey.get(e.getFromNodeId()) + "->" + synNodeKey.get(e.getToNodeId())
                    + ":" + e.getStrength());
        }
        Collections.sort(edgeParts);
        nodeParts.addAll(edgeParts);
        return String.join("|", nodeParts);
    }

    // ═══════════════════════════════════════════════════════════════
    // US1: pull（T009: Phase 3 实现）
    // ═══════════════════════════════════════════════════════════════

    public ProjectSyncDtos.PullResult pull(Long projectId, Long tenantId) {
        // 1. 隔离守卫
        Project project = requireOwnedProject(projectId, tenantId);

        // 2. 装配 8 聚合（data-model §2.1）
        List<CatalogNode> catalogs = catalogNodeRepository.findByProjectIdAndDeleted(projectId, 0);
        List<Tag> tags = tagRepository.findByProjectIdOrderByNameAsc(projectId);

        // entityTags: 汇总所有 tag 的关联
        List<EntityTag> entityTags = new ArrayList<>();
        for (Tag tag : tags) {
            entityTags.addAll(entityTagRepository.findByTagId(tag.getId()));
        }

        // tasks (deleted=0)
        List<TaskDef> tasks = new ArrayList<>();
        for (TaskDef t : taskDefRepository.findByProjectId(projectId)) {
            if (t.getDeleted() == null || t.getDeleted() == 0) tasks.add(t);
        }

        // workflows (deleted=0)
        List<WorkflowDef> workflows = new ArrayList<>();
        for (WorkflowDef w : workflowDefRepository.findByProjectId(projectId)) {
            if (w.getDeleted() == null || w.getDeleted() == 0) workflows.add(w);
        }

        // workflow nodes + edges
        List<WorkflowNode> allNodes = new ArrayList<>();
        List<WorkflowEdge> allEdges = new ArrayList<>();
        for (WorkflowDef wf : workflows) {
            allNodes.addAll(workflowNodeRepository.findByWorkflowIdAndDeleted(wf.getId(), 0));
            allEdges.addAll(workflowEdgeRepository.findByWorkflowIdAndDeleted(wf.getId(), 0));
        }

        // 3. Slug 映射（013: 同目录兄弟集内经 uniquify 保证唯一 + INV-4 导出=身份同源）
        Map<Long, Long> taskGroupById = new LinkedHashMap<>();
        for (TaskDef t : tasks) {
            taskGroupById.put(t.getId(), t.getCatalogNodeId()); // null = root
        }
        Map<Long, Long> wfGroupById = new LinkedHashMap<>();
        for (WorkflowDef w : workflows) {
            wfGroupById.put(w.getId(), w.getCatalogNodeId()); // null = root
        }
        Map<Long, String> taskSlugs = computeUniqueSlugs(
                tasks.stream().map(t -> Map.entry(t.getId(), t.getName())).toList(),
                taskGroupById);
        Map<Long, String> workflowSlugs = computeUniqueSlugs(
                workflows.stream().map(w -> Map.entry(w.getId(), w.getName())).toList(),
                wfGroupById);

        // 4. DataSource 逻辑名映射（D7: 零凭据，只取 name）
        List<Datasource> datasources = datasourceRepository.findByProjectId(projectId);
        Map<Long, String> dsIdToName = new HashMap<>();
        for (Datasource ds : datasources) {
            dsIdToName.put(ds.getId(), ds.getName());
        }
        Map<Long, String> taskDatasourceCodes = new LinkedHashMap<>();
        Map<Long, String> taskTargetDatasourceCodes = new LinkedHashMap<>();
        for (TaskDef t : tasks) {
            if (t.getDatasourceId() != null && dsIdToName.containsKey(t.getDatasourceId())) {
                taskDatasourceCodes.put(t.getId(), dsIdToName.get(t.getDatasourceId()));
            }
            if (t.getTargetDatasourceId() != null && dsIdToName.containsKey(t.getTargetDatasourceId())) {
                taskTargetDatasourceCodes.put(t.getId(), dsIdToName.get(t.getTargetDatasourceId()));
            }
        }

        // 5. 装配 ProjectExport → 序列化
        ProjectExport export = new ProjectExport(project, catalogs, tags, entityTags,
                tasks, workflows, allNodes, allEdges,
                taskSlugs, workflowSlugs, taskDatasourceCodes, taskTargetDatasourceCodes);

        FileContract fc = new FileContract();
        ProjectFileBundle bundle = fc.serialize(export);

        // 6. 基线令牌
        String baseline = computeBaseline(projectId);

        return new ProjectSyncDtos.PullResult(projectId,
                ProjectSyncDtos.SyncBundle.of(bundle.files()),
                baseline,
                bundle.size());
    }

    // ═══════════════════════════════════════════════════════════════
    // US2: push（T014-T016: Phase 4 实现）
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public ProjectSyncDtos.PushResult push(Long projectId, Long tenantId, Long userId, ProjectSyncDtos.PushCommand cmd) {
        // ── 1. 隔离守卫 ──
        Project project = requireOwnedProject(projectId, tenantId);
        LocalDateTime now = LocalDateTime.now();

        // ── 2. 反序列化 ──
        FileContract fc = new FileContract();
        ProjectFileBundle bundle = new ProjectFileBundle(cmd.files());
        ProjectImport imported;
        try {
            imported = fc.deserialize(bundle);
        } catch (Exception e) {
            throw new BizException("project.sync.invalid", e.getMessage());
        }

        // ── 3. 校验全前置（任一失败 → 整单拒绝，零落库，D5/FR-006）──

        // 3a. Warnings
        if (!imported.warnings().isEmpty()) {
            String detail = String.join("; ", imported.warnings());
            throw new BizException("project.sync.invalid", detail);
        }

        // 3b. 文件完整性
        if (cmd.expectedFileCount() != null && cmd.expectedFileCount() > 0
                && cmd.files().size() != cmd.expectedFileCount()) {
            throw new BizException("project.sync.incomplete",
                    cmd.files().size(), cmd.expectedFileCount());
        }

        // 3c. 基线并发校验
        if (cmd.baseline() != null && !cmd.baseline().isEmpty() && !cmd.force()) {
            String currentBaseline = computeBaseline(projectId);
            if (!currentBaseline.equals(cmd.baseline())) {
                throw new BizException("project.sync.stale");
            }
        }

        // 3d. Datasource 名解析（D3/FR-007）
        List<Datasource> datasources = datasourceRepository.findByProjectId(projectId);
        Map<String, Long> dsNameToId = new HashMap<>();
        for (Datasource ds : datasources) {
            dsNameToId.put(ds.getName(), ds.getId());
        }
        Map<Long, String> taskDsCodes = imported.taskDatasourceCodes();
        Map<Long, String> taskTargetDsCodes = imported.taskTargetDatasourceCodes();
        for (TaskDef t : imported.tasks()) {
            String dsName = taskDsCodes.get(t.getId());
            if (dsName != null && !dsNameToId.containsKey(dsName)) {
                throw new BizException("project.sync.unknown_datasource", t.getName(), dsName);
            }
            String targetDsName = taskTargetDsCodes.get(t.getId());
            if (targetDsName != null && !dsNameToId.containsKey(targetDsName)) {
                throw new BizException("project.sync.unknown_datasource", t.getName(), targetDsName);
            }
        }

        // 在任何 id mutation 前,捕获 合成 id → 稳定身份(供下游按身份解析,避免 insert 改 id 后失配)
        Map<Long, String> synTagIdToName = new HashMap<>();
        for (Tag t : imported.tags()) {
            synTagIdToName.put(t.getId(), t.getName());
        }
        Map<Long, String> synCatIdToPath = new HashMap<>();
        for (CatalogNode c : imported.catalogs()) {
            synCatIdToPath.put(c.getId(), c.getPath());
        }

        // ── 4. 身份对账 ──
        ReconResult recon = reconcile(imported, projectId);

        // 3e. 删除在线引用守卫（D6：对账后才能判断哪些 task 被删）
        for (TaskDef delTask : recon.deleteTasks()) {
            List<WorkflowNode> refNodes = workflowNodeRepository.findByTaskIdAndDeleted(delTask.getId(), 0);
            for (WorkflowNode node : refNodes) {
                workflowDefRepository.findById(node.getWorkflowId()).ifPresent(wf -> {
                    if ("ONLINE".equals(wf.getStatus())) {
                        throw new BizException("project.sync.delete_referenced",
                                delTask.getName(), wf.getName());
                    }
                });
            }
        }

        // ── 5. 落库（校验全过后）──

        // 5a. Catalogs: 构建 path→id 映射供后续使用
        Map<String, Long> catalogPathToId = new HashMap<>();
        for (CatalogNode c : catalogNodeRepository.findByProjectIdAndDeleted(projectId, 0)) {
            catalogPathToId.put(c.getPath(), c.getId());
        }

        ProjectSyncDtos.Counts created = new ProjectSyncDtos.Counts(0, 0, 0, 0);
        ProjectSyncDtos.Counts updated = new ProjectSyncDtos.Counts(0, 0, 0, 0);
        ProjectSyncDtos.Counts deleted = new ProjectSyncDtos.Counts(0, 0, 0, 0);

        // Catalogs: insert
        for (CatalogNode cat : recon.insertCatalogs()) {
            cat.setId(null);
            cat.setTenantId(tenantId);
            cat.setProjectId(projectId);
            cat.setCreatedBy(userId);
            cat.setUpdatedBy(userId);
            cat.setCreatedAt(now);
            cat.setUpdatedAt(now);
            cat.setDeleted(0);
            cat.setVersion(0L);
            catalogNodeRepository.save(cat);
            catalogPathToId.put(cat.getPath(), cat.getId());
            created = new ProjectSyncDtos.Counts(created.task(), created.workflow(),
                    created.catalog() + 1, created.tag());
        }
        // Catalogs: update
        for (CatalogNode cat : recon.updateCatalogs()) {
            CatalogNode srv = catalogNodeRepository.findById(cat.getId()).orElse(null);
            if (srv != null) {
                srv.setName(cat.getName());
                srv.setUpdatedBy(userId);
                srv.setUpdatedAt(now);
                catalogNodeRepository.save(srv);
                updated = new ProjectSyncDtos.Counts(updated.task(), updated.workflow(),
                        updated.catalog() + 1, updated.tag());
            }
        }
        // Catalogs: delete
        for (CatalogNode cat : recon.deleteCatalogs()) {
            cat.setDeleted(1);
            cat.setUpdatedAt(now);
            catalogNodeRepository.save(cat);
            deleted = new ProjectSyncDtos.Counts(deleted.task(), deleted.workflow(),
                    deleted.catalog() + 1, deleted.tag());
        }

        // Refresh catalog path→id after inserts
        for (CatalogNode c : catalogNodeRepository.findByProjectIdAndDeleted(projectId, 0)) {
            catalogPathToId.put(c.getPath(), c.getId());
        }
        // Build 合成 catalogId → 真实 catalogId(经 path;用 mutation 前捕获的 synCatIdToPath,
        // 因 insert 已把 imported.catalogs() 的 id 改成真实,不能再读其 getId())
        Map<Long, Long> localCatIdToReal = new HashMap<>();
        for (Map.Entry<Long, String> e : synCatIdToPath.entrySet()) {
            Long realId = catalogPathToId.get(e.getValue());
            if (realId != null) localCatIdToReal.put(e.getKey(), realId);
        }
        // 解析新建类目的 parentId(合成→真实):B 给的 parentId 是合成 id,需回填真实邻接(FR-008 类目树)
        for (CatalogNode cat : recon.insertCatalogs()) {
            if (cat.getParentId() != null) {
                Long realParent = localCatIdToReal.get(cat.getParentId());
                if (realParent != null && !realParent.equals(cat.getParentId())) {
                    cat.setParentId(realParent);
                    cat.setUpdatedAt(now);
                    catalogNodeRepository.save(cat);
                }
            }
        }

        // 5b. Tags
        Map<String, Long> tagNameToId = new HashMap<>();
        for (Tag t : tagRepository.findByProjectIdOrderByNameAsc(projectId)) {
            tagNameToId.put(t.getName(), t.getId());
        }
        for (Tag tag : recon.insertTags()) {
            tag.setId(null);
            tag.setTenantId(tenantId);
            tag.setProjectId(projectId);
            tag.setCreatedBy(userId);
            tag.setUpdatedBy(userId);
            tag.setCreatedAt(now);
            tag.setUpdatedAt(now);
            tagRepository.save(tag);
            tagNameToId.put(tag.getName(), tag.getId());
            created = new ProjectSyncDtos.Counts(created.task(), created.workflow(),
                    created.catalog(), created.tag() + 1);
        }
        for (Tag tag : recon.updateTags()) {
            Tag srv = tagRepository.findById(tag.getId()).orElse(null);
            if (srv != null) {
                srv.setColor(tag.getColor());
                srv.setUpdatedBy(userId);
                srv.setUpdatedAt(now);
                tagRepository.save(srv);
                updated = new ProjectSyncDtos.Counts(updated.task(), updated.workflow(),
                        updated.catalog(), updated.tag() + 1);
            }
        }
        for (Tag tag : recon.deleteTags()) {
            entityTagRepository.deleteByTagId(tag.getId());
            tagRepository.delete(tag);
            deleted = new ProjectSyncDtos.Counts(deleted.task(), deleted.workflow(),
                    deleted.catalog(), deleted.tag() + 1);
        }

        // 5c. Tasks —— 合成→真实 id 映射:reconcile 已填匹配项(更新+未变更),insert 在此补全。
        Map<Long, Long> synTaskToReal = new HashMap<>(recon.synTaskIdToRealId());
        Set<Long> changedTaskReal = new HashSet<>(); // insert + update → 建快照
        for (TaskDef task : recon.insertTasks()) {
            Long syn = task.getId();
            task.setId(null);
            task.setTenantId(tenantId);
            task.setProjectId(projectId);
            if (task.getCatalogNodeId() != null) {
                task.setCatalogNodeId(localCatIdToReal.get(task.getCatalogNodeId()));
            }
            String dsName = taskDsCodes.get(syn);              // 按合成 id 取 ds code(修:原读 null key)
            if (dsName != null) task.setDatasourceId(dsNameToId.get(dsName));
            String targetDsName = taskTargetDsCodes.get(syn);
            if (targetDsName != null) task.setTargetDatasourceId(dsNameToId.get(targetDsName));
            task.setStatus("DRAFT");
            task.setCurrentVersionNo(0);
            task.setHasDraftChange(1);
            task.setFrozen(0);
            task.setCreatedBy(userId);
            task.setUpdatedBy(userId);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            task.setDeleted(0);
            task.setVersion(0L);
            taskDefRepository.save(task);
            synTaskToReal.put(syn, task.getId());              // 合成→真实
            changedTaskReal.add(task.getId());
            created = created.plusTask();
        }
        for (TaskDef localTask : recon.updateTasks()) {
            Long syn = localTask.getId();                      // reconcile 已不再 setId,仍是合成 id
            Long realId = synTaskToReal.get(syn);
            TaskDef srv = realId != null ? taskDefRepository.findById(realId).orElse(null) : null;
            if (srv != null) {
                srv.setName(localTask.getName());
                srv.setType(localTask.getType());
                srv.setContent(localTask.getContent());
                String dsName = taskDsCodes.get(syn);
                srv.setDatasourceId(dsName != null ? dsNameToId.get(dsName) : null);
                String targetDsName = taskTargetDsCodes.get(syn);
                srv.setTargetDatasourceId(targetDsName != null ? dsNameToId.get(targetDsName) : null);
                srv.setParamsJson(localTask.getParamsJson());
                srv.setTimeoutSec(localTask.getTimeoutSec());
                srv.setRetryMax(localTask.getRetryMax());
                srv.setPriority(localTask.getPriority());
                srv.setDescription(localTask.getDescription());
                if (localTask.getCatalogNodeId() != null) {
                    srv.setCatalogNodeId(localCatIdToReal.get(localTask.getCatalogNodeId()));
                }
                srv.setHasDraftChange(1);
                srv.setUpdatedBy(userId);
                srv.setUpdatedAt(now);
                taskDefRepository.save(srv);
                changedTaskReal.add(srv.getId());
                updated = updated.plusTask();
            }
        }
        for (TaskDef task : recon.deleteTasks()) {
            task.setDeleted(1);
            task.setUpdatedAt(now);
            taskDefRepository.save(task);
            deleted = deleted.plusTask();
        }

        // 5c-lineage. push 落库的新增/修改任务补血缘入图（US2，FR-002 缺口补齐）。
        // 复用 createAndOnline 同款装配（LineageEdgeAssembler A×B）；try-catch 不阻断 push 主链路（FR-007）。
        for (Long taskId : changedTaskReal) {
            try {
                TaskDef t = taskDefRepository.findById(taskId).orElse(null);
                if (t == null) {
                    continue;
                }
                LineageEdgeAssembler.Assembly assembly = lineageEdgeAssembler.assemble(
                        tenantId, projectId, t.getType(), t.getContent(),
                        null, null, t.getDatasourceId(), t.getTargetDatasourceId());
                // 列级边：019 解析 → adapter 转 domain（源←读侧 coord，目标←写侧 coord）
                java.util.List<com.dataweave.master.domain.lineage.ColumnEdge> columnEdges = java.util.List.of();
                if ("SQL".equalsIgnoreCase(t.getType())) {
                    var colResult = sqlColumnLineageExtractor.extract(
                            t.getContent(), com.dataweave.master.application.lineage.ColumnLineageCatalog.EMPTY);
                    columnEdges = com.dataweave.master.application.lineage.ColumnLineageStoreAdapter.toDomain(
                            colResult,
                            lineageEdgeAssembler.resolveCoord(tenantId, projectId, t.getDatasourceId()),
                            lineageEdgeAssembler.resolveCoord(tenantId, projectId, t.getTargetDatasourceId()));
                }
                if (!assembly.ioEdges().isEmpty() || !columnEdges.isEmpty()) {
                    lineageStore.recordTaskIo(tenantId, projectId, taskId,
                            t.getCurrentVersionNo(), t.getName(),
                            assembly.ioEdges(), columnEdges);
                }
            } catch (Exception e) {
                log.warn("push lineage record skipped for task {} (FR-007): {}", taskId, e.toString());
            }
        }

        // 5d. Workflows —— 同样保留 合成→真实 映射。
        Map<Long, Long> synWfToReal = new HashMap<>(recon.synWfIdToRealId());
        Set<Long> changedWfReal = new HashSet<>(); // insert + config-update（DAG 变更在 5e 追加）
        for (WorkflowDef wf : recon.insertWorkflows()) {
            Long syn = wf.getId();
            wf.setId(null);
            wf.setTenantId(tenantId);
            wf.setProjectId(projectId);
            if (wf.getCatalogNodeId() != null) {
                wf.setCatalogNodeId(localCatIdToReal.get(wf.getCatalogNodeId()));
            }
            wf.setStatus("DRAFT");
            wf.setCurrentVersionNo(0);
            wf.setHasDraftChange(1);
            wf.setCreatedBy(userId);
            wf.setUpdatedBy(userId);
            wf.setCreatedAt(now);
            wf.setUpdatedAt(now);
            wf.setDeleted(0);
            wf.setVersion(0L);
            workflowDefRepository.save(wf);
            synWfToReal.put(syn, wf.getId());
            changedWfReal.add(wf.getId());
            created = created.plusWorkflow();
        }
        for (WorkflowDef localWf : recon.updateWorkflows()) {
            Long syn = localWf.getId();
            Long realId = synWfToReal.get(syn);
            WorkflowDef srv = realId != null ? workflowDefRepository.findById(realId).orElse(null) : null;
            if (srv != null) {
                srv.setName(localWf.getName());
                srv.setDescription(localWf.getDescription());
                srv.setScheduleType(localWf.getScheduleType());
                srv.setCron(localWf.getCron());
                if (localWf.getCatalogNodeId() != null) {
                    srv.setCatalogNodeId(localCatIdToReal.get(localWf.getCatalogNodeId()));
                }
                srv.setHasDraftChange(1);
                srv.setUpdatedBy(userId);
                srv.setUpdatedAt(now);
                workflowDefRepository.save(srv);
                changedWfReal.add(srv.getId());
                updated = updated.plusWorkflow();
            }
        }
        for (WorkflowDef wf : recon.deleteWorkflows()) {
            for (WorkflowNode n : workflowNodeRepository.findByWorkflowIdAndDeleted(wf.getId(), 0)) {
                n.setDeleted(1); n.setUpdatedAt(now); workflowNodeRepository.save(n);
            }
            for (WorkflowEdge e : workflowEdgeRepository.findByWorkflowIdAndDeleted(wf.getId(), 0)) {
                e.setDeleted(1); e.setUpdatedAt(now); workflowEdgeRepository.save(e);
            }
            wf.setDeleted(1);
            wf.setUpdatedAt(now);
            workflowDefRepository.save(wf);
            deleted = deleted.plusWorkflow();
        }

        // 5e. Workflow nodes/edges 整体重建 —— 遍历 合成→真实 映射(含配置未变但 DAG 可能变的匹配项)。
        //     注意:不能用 imported.workflows() 的 getId()(insert 已把其 id 改成真实),节点仍按 合成 wfId 关联。
        //     先比对 DAG 签名:无变更则跳过(避免无谓 churn 与快照),有变更才重建并标记建快照。
        for (Map.Entry<Long, Long> wfEntry : synWfToReal.entrySet()) {
            Long synWfId = wfEntry.getKey();
            Long realWfId = wfEntry.getValue();
            if (realWfId == null) continue;

            String beforeSig = dagSignature(realWfId);
            String desiredSig = desiredDagSignature(synWfId, imported, synTaskToReal);
            if (desiredSig.equals(beforeSig)) continue; // DAG 无变更

            // 删旧 node/edge
            for (WorkflowNode n : workflowNodeRepository.findByWorkflowIdAndDeleted(realWfId, 0)) {
                n.setDeleted(1); n.setUpdatedAt(now); workflowNodeRepository.save(n);
            }
            for (WorkflowEdge e : workflowEdgeRepository.findByWorkflowIdAndDeleted(realWfId, 0)) {
                e.setDeleted(1); e.setUpdatedAt(now); workflowEdgeRepository.save(e);
            }

            // 插新 nodes（合成 nodeId → 真实 nodeId）
            Map<Long, Long> synNodeToReal = new HashMap<>();
            for (WorkflowNode node : imported.workflowNodes()) {
                if (!node.getWorkflowId().equals(synWfId)) continue;
                Long synNodeId = node.getId();
                node.setId(null);
                node.setTenantId(tenantId);
                node.setProjectId(projectId);
                node.setWorkflowId(realWfId);
                if (node.getTaskId() != null) {
                    node.setTaskId(synTaskToReal.get(node.getTaskId()));  // 合成 taskId → 真实(修:原取 null)
                }
                node.setCreatedBy(userId);
                node.setUpdatedBy(userId);
                node.setCreatedAt(now);
                node.setUpdatedAt(now);
                node.setDeleted(0);
                node.setVersion(0L);
                workflowNodeRepository.save(node);
                synNodeToReal.put(synNodeId, node.getId());
            }

            // 插新 edges
            for (WorkflowEdge edge : imported.workflowEdges()) {
                if (!edge.getWorkflowId().equals(synWfId)) continue;
                edge.setId(null);
                edge.setTenantId(tenantId);
                edge.setProjectId(projectId);
                edge.setWorkflowId(realWfId);
                edge.setFromNodeId(synNodeToReal.get(edge.getFromNodeId()));
                edge.setToNodeId(synNodeToReal.get(edge.getToNodeId()));
                edge.setCreatedBy(userId);
                edge.setUpdatedBy(userId);
                edge.setCreatedAt(now);
                edge.setUpdatedAt(now);
                edge.setDeleted(0);
                edge.setVersion(0L);
                workflowEdgeRepository.save(edge);
            }

            changedWfReal.add(realWfId); // DAG 变更 → 建快照
        }

        // 5f. EntityTags 重建（受影响实体 = 本次 push 涉及的全部匹配+新增 task/workflow）。
        Set<Long> affectedTaskReal = new HashSet<>(synTaskToReal.values());
        Set<Long> affectedWfReal = new HashSet<>(synWfToReal.values());
        for (Long taskId : affectedTaskReal) {
            entityTagRepository.deleteByEntityTypeAndEntityId(EntityTag.TYPE_TASK, taskId);
        }
        for (Long wfId : affectedWfReal) {
            entityTagRepository.deleteByEntityTypeAndEntityId(EntityTag.TYPE_WORKFLOW, wfId);
        }
        for (EntityTag et : imported.entityTags()) {
            Long realEntityId = null;
            if (EntityTag.TYPE_TASK.equals(et.getEntityType())) {
                realEntityId = synTaskToReal.get(et.getEntityId());
            } else if (EntityTag.TYPE_WORKFLOW.equals(et.getEntityType())) {
                realEntityId = synWfToReal.get(et.getEntityId());
            }
            if (realEntityId == null) continue;
            // 合成 tagId → name → 真实 tagId(用 push 前捕获的快照,避免 insert 改 id 后失配)
            String tagName = synTagIdToName.get(et.getTagId());
            Long realTagId = tagName != null ? tagNameToId.get(tagName) : null;
            if (realTagId == null) continue;

            EntityTag newEt = new EntityTag();
            newEt.setTagId(realTagId);
            newEt.setEntityType(et.getEntityType());
            newEt.setEntityId(realEntityId);
            newEt.setCreatedAt(now);
            entityTagRepository.save(newEt);
        }

        // ── 6. 建快照（D1: 只调状态中立内核,不晋级 ONLINE;仅对 受影响 的 task/workflow）──
        List<ProjectSyncDtos.SnapshotRef> snapshots = new ArrayList<>();
        String remark = cmd.remark() != null ? cmd.remark() : "push";

        for (Long taskId : changedTaskReal) {
            TaskDef t = taskDefRepository.findById(taskId).orElse(null);
            if (t != null) {
                int vno = taskService.writeTaskVersionSnapshot(t, userId, remark);
                snapshots.add(new ProjectSyncDtos.SnapshotRef("TASK", taskId, t.getName(), vno));
            }
        }
        for (Long wfId : changedWfReal) {
            int vno = workflowService.writeWorkflowVersionSnapshot(wfId, remark);
            WorkflowDef w = workflowDefRepository.findById(wfId).orElse(null);
            snapshots.add(new ProjectSyncDtos.SnapshotRef("WORKFLOW", wfId,
                    w != null ? w.getName() : "", vno));
        }

        // ── 7. 新基线 ──
        String newBaseline = computeBaseline(projectId);

        return new ProjectSyncDtos.PushResult(projectId, created, updated, deleted,
                snapshots, newBaseline);
    }

    // ═══════════════════════════════════════════════════════════════
    // US3: diff（T022: Phase 5 实现）
    // ═══════════════════════════════════════════════════════════════

    public ProjectSyncDtos.DiffPreview diff(Long projectId, Long tenantId, ProjectSyncDtos.PushCommand cmd) {
        // 1. 隔离守卫
        requireOwnedProject(projectId, tenantId);

        // 2. 反序列化
        FileContract fc = new FileContract();
        ProjectFileBundle bundle = new ProjectFileBundle(cmd.files());
        ProjectImport imported;
        try {
            imported = fc.deserialize(bundle);
        } catch (Exception e) {
            throw new BizException("project.sync.invalid", e.getMessage());
        }

        if (!imported.warnings().isEmpty()) {
            String detail = String.join("; ", imported.warnings());
            throw new BizException("project.sync.invalid", detail);
        }

        // 3. 身份对账（只读，复用 T007 reconcile）
        ReconResult recon = reconcile(imported, projectId);

        // 4. 陈旧检测
        boolean stale = false;
        if (cmd.baseline() != null && !cmd.baseline().isEmpty()) {
            String currentBaseline = computeBaseline(projectId);
            stale = !currentBaseline.equals(cmd.baseline());
        }

        // 5. 返回只读预览（不写库，FR-011）
        return new ProjectSyncDtos.DiffPreview(
                recon.added(), recon.modified(), recon.removed(), stale);
    }
}
