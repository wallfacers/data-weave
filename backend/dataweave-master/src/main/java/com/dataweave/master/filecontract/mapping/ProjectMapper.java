package com.dataweave.master.filecontract.mapping;

import com.dataweave.master.domain.*;
import com.dataweave.master.filecontract.ProjectExport;
import com.dataweave.master.filecontract.ProjectFileBundle;
import com.dataweave.master.filecontract.ProjectImport;
import com.dataweave.master.filecontract.dto.*;
import com.dataweave.master.filecontract.error.FileContractException;
import com.dataweave.master.filecontract.naming.EntityNaming;
import com.dataweave.master.filecontract.naming.SlugRules;
import com.dataweave.master.filecontract.yaml.DeterministicYaml;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Orchestrates all sub-mappers to serialize/deserialize a complete project.
 *
 * <p>Converts between:
 * <ul>
 *   <li>{@link ProjectExport} (domain aggregate) → {@link ProjectFileBundle} (in-memory file tree)</li>
 *   <li>{@link ProjectFileBundle} → {@link ProjectImport} (domain aggregate + warnings)</li>
 * </ul>
 *
 * <p>Deserialization assigns deterministic synthetic ids (functions of sorted file paths)
 * and wires every foreign key, so {@code serialize(deserialize(bundle).toExport())} reproduces
 * the bundle byte-for-byte (FR-011②/SC-002). Identity stays path/slug-based (FR-007); the
 * synthetic ids are internal scaffolding the server reassigns on ingest.
 */
public class ProjectMapper {

    private final DeterministicYaml yaml;
    private final ObjectMapper jsonMapper;
    private final TagMapper tagMapper;
    private final TaskMapper taskMapper;
    private final WorkflowMapper workflowMapper;
    private final CatalogMapper catalogMapper;

    // Fixed filenames in project root
    static final String PROJECT_YAML = "project.yaml";
    static final String TAGS_YAML = "tags.yaml";
    static final String TASK_SUFFIX = ".task.yaml";
    static final String FLOW_SUFFIX = ".flow.yaml";

    public ProjectMapper(DeterministicYaml yaml, ObjectMapper jsonMapper) {
        this.yaml = yaml;
        this.jsonMapper = jsonMapper;
        this.tagMapper = new TagMapper(yaml);
        this.taskMapper = new TaskMapper(yaml, jsonMapper);
        this.workflowMapper = new WorkflowMapper(yaml);
        this.catalogMapper = new CatalogMapper(yaml);
    }

    // Package-visible for FileContract
    TagMapper tagMapper() { return tagMapper; }
    TaskMapper taskMapper() { return taskMapper; }
    WorkflowMapper workflowMapper() { return workflowMapper; }
    CatalogMapper catalogMapper() { return catalogMapper; }

    // =========================================================================
    // Serialize: ProjectExport → ProjectFileBundle
    // =========================================================================

    public ProjectFileBundle serialize(ProjectExport export) {
        var files = new LinkedHashMap<String, String>();

        // 1. project.yaml
        var projectDoc = new ProjectDoc(ProjectDoc.CURRENT_FORMAT_VERSION,
                export.project().getCode(), export.project().getName());
        files.put(PROJECT_YAML, serializeProjectDoc(projectDoc));

        // 2. tags.yaml
        var tagsDoc = tagMapper.toTagsDoc(export.tags());
        files.put(TAGS_YAML, tagMapper.serialize(tagsDoc));

        // 3. catalogNodeId → directory path
        var nodePathMap = buildNodePathMap(export.catalogs());

        // 4. _folder.yaml for each catalog node
        for (var node : export.catalogs()) {
            var dirPath = nodePathMap.getOrDefault(node.getId(), "");
            var folderDoc = catalogMapper.toFolderDoc(node);
            var folderPath = dirPath.isEmpty() ? CatalogMapper.FOLDER_MARKER
                    : dirPath + "/" + CatalogMapper.FOLDER_MARKER;
            files.put(folderPath, catalogMapper.serialize(folderDoc));
        }

        // 5. Tasks + EntityTags
        var entityTagsByTask = buildEntityTagIndex(export.entityTags(), EntityTag.TYPE_TASK);

        for (var task : export.tasks()) {
            var dirPath = nodePathMap.getOrDefault(task.getCatalogNodeId(), "");
            var slug = export.taskSlugs().getOrDefault(task.getId(), deriveTaskSlug(task));
            SlugRules.validateSlug(slug, "task slug", dirPath + "/" + slug);

            var baseDoc = taskMapper.toTaskDoc(task);
            var tagNames = resolveTagNames(task.getId(), entityTagsByTask, export.tags());
            var docWithExtras = new TaskDoc(baseDoc.formatVersion(), baseDoc.name(), baseDoc.type(),
                    baseDoc.description(), baseDoc.priority(), baseDoc.timeoutSec(),
                    baseDoc.retryMax(), baseDoc.frozen(),
                    export.taskDatasourceCodes().get(task.getId()),
                    export.taskTargetDatasourceCodes().get(task.getId()),
                    baseDoc.params(),
                    tagNames.isEmpty() ? null : tagNames.stream().sorted().toList(),
                    baseDoc.sparkMode(), baseDoc.jarRef(), baseDoc.mainClass(),
                    baseDoc.declaredSchema(), baseDoc.declaredColumnLineage());
            var taskPath = dirPath.isEmpty() ? slug + TASK_SUFFIX : dirPath + "/" + slug + TASK_SUFFIX;
            files.put(taskPath, taskMapper.serialize(docWithExtras));

            // Script file
            if (task.getContent() != null) {
                var ext = TaskMapper.getScriptExtension(task.getType());
                var scriptPath = dirPath.isEmpty() ? slug + ext : dirPath + "/" + slug + ext;
                files.put(scriptPath, task.getContent());
            }
        }

        // 6. Workflows
        var nodesByWorkflow = groupNodesByWorkflow(export.workflowNodes());
        var edgesByWorkflow = groupEdgesByWorkflow(export.workflowEdges());
        var entityTagsByWf = buildEntityTagIndex(export.entityTags(), EntityTag.TYPE_WORKFLOW);

        for (var wf : export.workflows()) {
            var dirPath = nodePathMap.getOrDefault(wf.getCatalogNodeId(), "");
            var slug = export.workflowSlugs().getOrDefault(wf.getId(), deriveWorkflowSlug(wf));
            SlugRules.validateSlug(slug, "workflow slug", dirPath + "/" + slug);

            var wfNodes = nodesByWorkflow.getOrDefault(wf.getId(), List.of());
            var wfEdges = edgesByWorkflow.getOrDefault(wf.getId(), List.of());

            var nodeKeyMap = new HashMap<Long, String>();
            var taskPathMap = new HashMap<Long, String>();
            for (var nd : wfNodes) {
                nodeKeyMap.put(nd.getId(), nd.getNodeKey());
                if (nd.getTaskId() != null && export.taskSlugs().containsKey(nd.getTaskId())) {
                    var taskCatalogId = findTaskCatalogId(nd.getTaskId(), export.tasks());
                    var taskDir = nodePathMap.getOrDefault(taskCatalogId, "");
                    var taskSlug = export.taskSlugs().get(nd.getTaskId());
                    taskPathMap.put(nd.getTaskId(),
                            taskDir.isEmpty() ? taskSlug : taskDir + "/" + taskSlug);
                }
            }

            var wfDoc = workflowMapper.toWorkflowDoc(wf, wfNodes, wfEdges, nodeKeyMap, taskPathMap);
            var tagNames = resolveTagNames(wf.getId(), entityTagsByWf, export.tags());
            var docWithTags = withWorkflowTags(wfDoc, tagNames);
            var wfPath = dirPath.isEmpty() ? slug + FLOW_SUFFIX : dirPath + "/" + slug + FLOW_SUFFIX;
            files.put(wfPath, workflowMapper.serialize(docWithTags));
        }

        return new ProjectFileBundle(files);
    }

    // =========================================================================
    // Deserialize: ProjectFileBundle → ProjectImport
    // =========================================================================

    public ProjectImport deserialize(ProjectFileBundle bundle) {
        var files = bundle.files();
        var builder = new ProjectImport.Builder();

        // 1. project.yaml (required)
        var projectYaml = files.get(PROJECT_YAML);
        if (projectYaml == null) {
            throw new FileContractException(PROJECT_YAML, "file",
                    "required file 'project.yaml' not found in bundle");
        }
        builder.project(parseProject(projectYaml));

        // 2. tags.yaml → assign tag ids deterministically (sorted by name)
        var tagByName = new LinkedHashMap<String, Tag>();
        var tagsYaml = files.get(TAGS_YAML);
        if (tagsYaml != null) {
            var tags = new ArrayList<>(tagMapper.fromYaml(tagsYaml, TAGS_YAML));
            tags.sort(Comparator.comparing(Tag::getName));
            long tagId = 1;
            for (var t : tags) {
                t.setId(tagId++);
                builder.addTag(t);
                tagByName.put(t.getName(), t);
            }
        }

        // 3. Discover _folder.yaml docs → assign catalog ids (sorted dirPath)
        var folderDocs = new LinkedHashMap<String, FolderDoc>();
        for (var entry : files.entrySet()) {
            var path = entry.getKey();
            if (path.equals(CatalogMapper.FOLDER_MARKER) || path.endsWith("/" + CatalogMapper.FOLDER_MARKER)) {
                var dirPath = path.equals(CatalogMapper.FOLDER_MARKER) ? ""
                        : path.substring(0, path.length() - CatalogMapper.FOLDER_MARKER.length() - 1);
                try {
                    folderDocs.put(dirPath, catalogMapper.fromYaml(entry.getValue(), path));
                } catch (FileContractException e) {
                    builder.addWarning(e.toString());
                }
            }
        }
        var sortedDirs = new ArrayList<>(folderDocs.keySet());
        Collections.sort(sortedDirs);
        var dirPathToCatalogId = new LinkedHashMap<String, Long>();
        var catalogByDir = new LinkedHashMap<String, CatalogNode>();
        long catalogId = 1;
        for (var dir : sortedDirs) {
            if (!dir.isEmpty()) {
                for (var seg : dir.split("/")) {
                    SlugRules.validateSlug(seg, "directory name", dir);
                }
            }
            var node = catalogMapper.toDomain(folderDocs.get(dir), dir);
            node.setId(catalogId);
            dirPathToCatalogId.put(dir, catalogId);
            catalogByDir.put(dir, node);
            catalogId++;
        }
        // parentId from directory nesting
        for (var e : catalogByDir.entrySet()) {
            var parentDir = parentDir(e.getKey());
            e.getValue().setParentId(parentDir == null ? null : dirPathToCatalogId.get(parentDir));
            builder.addCatalog(e.getValue());
        }

        // 4. Tasks: gather, sort by path, assign ids, wire FKs + slug/datasource maps
        var relTaskPathToId = new LinkedHashMap<String, Long>();
        var taskPaths = new ArrayList<String>();
        for (var path : files.keySet()) {
            if (path.endsWith(TASK_SUFFIX)) taskPaths.add(path);
        }
        Collections.sort(taskPaths);
        long taskId = 1;
        for (var path : taskPaths) {
            var dirPath = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
            var filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            var slug = filename.substring(0, filename.length() - TASK_SUFFIX.length());
            try {
                SlugRules.validateSlug(slug, "task filename", path);
                SlugRules.validateNotReserved(slug, path);
                var taskDoc = taskMapper.fromYaml(files.get(path), path);
                var ext = TaskMapper.getScriptExtension(taskDoc.type());
                var scriptPath = dirPath.isEmpty() ? slug + ext : dirPath + "/" + slug + ext;
                var scriptContent = files.get(scriptPath);
                if (scriptContent != null && scriptContent.length() > TaskMapper.MAX_CONTENT_LENGTH) {
                    throw new FileContractException(path, "content",
                            "script content exceeds server limit (" + TaskMapper.MAX_CONTENT_LENGTH + " chars)");
                }
                var task = taskMapper.toDomain(taskDoc, scriptContent);
                long id = taskId++;
                task.setId(id);
                task.setCatalogNodeId(dirPathToCatalogId.get(dirPath));
                builder.addTask(task);
                builder.taskSlug(id, slug);
                builder.taskDatasourceCode(id, taskDoc.datasource());
                builder.taskTargetDatasourceCode(id, taskDoc.targetDatasource());
                builder.taskDeclaredSchema(id, taskDoc.declaredSchema());
                builder.taskDeclaredColumnEdges(id, taskDoc.declaredColumnLineage());
                var relPath = dirPath.isEmpty() ? slug : dirPath + "/" + slug;
                relTaskPathToId.put(relPath, id);

                addEntityTags(builder, taskDoc.tags(), EntityTag.TYPE_TASK, id, tagByName, path);
            } catch (FileContractException e) {
                builder.addWarning(e.toString());
            }
        }

        // 5. Workflows: gather, sort by path, assign ids, wire nodes/edges
        var wfPaths = new ArrayList<String>();
        for (var path : files.keySet()) {
            if (path.endsWith(FLOW_SUFFIX)) wfPaths.add(path);
        }
        Collections.sort(wfPaths);
        long wfId = 1, nodeId = 1, edgeId = 1;
        for (var path : wfPaths) {
            var dirPath = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
            var filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            var slug = filename.substring(0, filename.length() - FLOW_SUFFIX.length());
            try {
                SlugRules.validateSlug(slug, "workflow filename", path);
                SlugRules.validateNotReserved(slug, path);
                var wfDoc = workflowMapper.fromYaml(files.get(path), path);
                var domainWf = workflowMapper.toDomain(wfDoc);
                var wf = domainWf.workflow();
                long thisWfId = wfId++;
                wf.setId(thisWfId);
                wf.setCatalogNodeId(dirPathToCatalogId.get(dirPath));
                builder.addWorkflow(wf);
                builder.workflowSlug(thisWfId, slug);

                // Nodes (domain order matches doc order)
                var nodeKeyToId = new LinkedHashMap<String, Long>();
                var nodes = domainWf.nodes();
                for (int i = 0; i < nodes.size(); i++) {
                    var node = nodes.get(i);
                    var nodeDoc = wfDoc.nodes().get(i);
                    long thisNodeId = nodeId++;
                    node.setId(thisNodeId);
                    node.setWorkflowId(thisWfId);
                    if (nodeDoc.task() != null) {
                        var refId = relTaskPathToId.get(nodeDoc.task());
                        if (refId == null) {
                            builder.addWarning(path + ": node '" + nodeDoc.key()
                                    + "' references unknown task '" + nodeDoc.task() + "'");
                        }
                        node.setTaskId(refId);
                    }
                    nodeKeyToId.put(node.getNodeKey(), thisNodeId);
                    builder.addWorkflowNode(node);
                }

                // Edges (domain order matches doc order)
                var edges = domainWf.edges();
                for (int i = 0; i < edges.size(); i++) {
                    var edge = edges.get(i);
                    var edgeDoc = wfDoc.edges().get(i);
                    edge.setId(edgeId++);
                    edge.setWorkflowId(thisWfId);
                    edge.setFromNodeId(nodeKeyToId.get(edgeDoc.from()));
                    edge.setToNodeId(nodeKeyToId.get(edgeDoc.to()));
                    builder.addWorkflowEdge(edge);
                }

                addEntityTags(builder, wfDoc.tags(), EntityTag.TYPE_WORKFLOW, thisWfId, tagByName, path);
            } catch (FileContractException e) {
                builder.addWarning(e.toString());
            }
        }

        return builder.build();
    }

    private void addEntityTags(ProjectImport.Builder builder, List<String> tagNames,
                               String entityType, long entityId,
                               Map<String, Tag> tagByName, String file) {
        if (tagNames == null) return;
        for (var tn : tagNames) {
            var tag = tagByName.get(tn);
            if (tag == null) {
                builder.addWarning(file + ": tag '" + tn + "' not defined in tags.yaml");
                continue;
            }
            var et = new EntityTag();
            et.setTagId(tag.getId());
            et.setEntityType(entityType);
            et.setEntityId(entityId);
            builder.addEntityTag(et);
        }
    }

    // =========================================================================
    // project.yaml helpers
    // =========================================================================

    String serializeProjectDoc(ProjectDoc doc) {
        var map = DeterministicYaml.orderedMap();
        DeterministicYaml.put(map, "formatVersion", doc.formatVersion());
        DeterministicYaml.put(map, "code", doc.code());
        DeterministicYaml.put(map, "name", doc.name());
        return yaml.dump(map);
    }

    Project parseProject(String yamlStr) {
        var raw = yaml.load(yamlStr);
        var code = TagMapper.requiredString(raw, "code", PROJECT_YAML);
        var name = TagMapper.requiredString(raw, "name", PROJECT_YAML);
        var project = new Project();
        project.setCode(code);
        project.setName(name);
        return project;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** Parent directory path, or null for the root (""). */
    private static String parentDir(String dir) {
        if (dir == null || dir.isEmpty()) return null;
        var lastSlash = dir.lastIndexOf('/');
        return lastSlash >= 0 ? dir.substring(0, lastSlash) : "";
    }

    private Map<Long, String> buildNodePathMap(List<CatalogNode> catalogs) {
        var map = new HashMap<Long, String>();
        for (var node : catalogs) {
            if (node.getPath() != null) {
                map.put(node.getId(), node.getPath());
            }
        }
        return map;
    }

    private Map<Long, List<WorkflowNode>> groupNodesByWorkflow(List<WorkflowNode> nodes) {
        var map = new HashMap<Long, List<WorkflowNode>>();
        for (var n : nodes) {
            map.computeIfAbsent(n.getWorkflowId(), k -> new ArrayList<>()).add(n);
        }
        return map;
    }

    private Map<Long, List<WorkflowEdge>> groupEdgesByWorkflow(List<WorkflowEdge> edges) {
        var map = new HashMap<Long, List<WorkflowEdge>>();
        for (var e : edges) {
            map.computeIfAbsent(e.getWorkflowId(), k -> new ArrayList<>()).add(e);
        }
        return map;
    }

    private Map<Long, List<EntityTag>> buildEntityTagIndex(List<EntityTag> tags, String entityType) {
        var map = new HashMap<Long, List<EntityTag>>();
        for (var et : tags) {
            if (entityType.equals(et.getEntityType())) {
                map.computeIfAbsent(et.getEntityId(), k -> new ArrayList<>()).add(et);
            }
        }
        return map;
    }

    private List<String> resolveTagNames(Long entityId, Map<Long, List<EntityTag>> index, List<Tag> allTags) {
        var entityTags = index.get(entityId);
        if (entityTags == null) return List.of();
        var tagIdToName = new HashMap<Long, String>();
        for (var t : allTags) tagIdToName.put(t.getId(), t.getName());
        return entityTags.stream()
                .map(et -> tagIdToName.get(et.getTagId()))
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }

    private Long findTaskCatalogId(Long taskId, List<TaskDef> tasks) {
        return tasks.stream()
                .filter(t -> taskId.equals(t.getId()))
                .findFirst()
                .map(TaskDef::getCatalogNodeId)
                .orElse(null);
    }

    /** Fallback slug derivation — delegates to {@link EntityNaming} single source of truth (013). */
    private String deriveTaskSlug(TaskDef task) {
        return EntityNaming.effectiveSlug(task.getName());
    }

    /** Fallback slug derivation — delegates to {@link EntityNaming} single source of truth (013). */
    private String deriveWorkflowSlug(WorkflowDef wf) {
        return EntityNaming.effectiveSlug(wf.getName());
    }

    private WorkflowDoc withWorkflowTags(WorkflowDoc doc, List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) return doc;
        return new WorkflowDoc(doc.formatVersion(), doc.name(), doc.description(),
                doc.schedule(), doc.priority(), doc.preemptible(), doc.timeoutSec(),
                tagNames.stream().sorted().toList(), doc.nodes(), doc.edges());
    }
}
