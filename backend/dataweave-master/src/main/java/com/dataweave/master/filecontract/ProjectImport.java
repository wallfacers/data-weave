package com.dataweave.master.filecontract;

import com.dataweave.master.domain.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain aggregate produced by deserialization, plus validation results.
 *
 * <p>Deserialization assigns deterministic synthetic ids (functions of the file
 * paths, sorted) and wires foreign keys so the aggregate is internally consistent
 * and <em>re-serializable byte-for-byte</em> (FR-011②/SC-002). Identity itself stays
 * path/slug-based (FR-007); synthetic ids are an internal device and are discarded /
 * reassigned by the server on ingest.
 *
 * <p>{@code taskSlugs}/{@code workflowSlugs} record each entity's file slug, and
 * {@code taskDatasourceCodes}/{@code taskTargetDatasourceCodes} carry the datasource
 * codes that have no home on {@link TaskDef} — together they let {@link #toExport()}
 * reproduce the original bundle.
 */
public record ProjectImport(
        Project project,
        List<CatalogNode> catalogs,
        List<Tag> tags,
        List<EntityTag> entityTags,
        List<TaskDef> tasks,
        List<WorkflowDef> workflows,
        List<WorkflowNode> workflowNodes,
        List<WorkflowEdge> workflowEdges,
        Map<Long, String> taskSlugs,
        Map<Long, String> workflowSlugs,
        Map<Long, String> taskDatasourceCodes,
        Map<Long, String> taskTargetDatasourceCodes,
        Map<Long, java.util.Map<String, java.util.List<com.dataweave.master.filecontract.dto.ColumnSchemaDecl>>> taskDeclaredSchema,
        Map<Long, java.util.List<Map<String, String>>> taskDeclaredColumnEdges,
        List<String> warnings
) {
    public ProjectImport {
        if (warnings == null) warnings = new ArrayList<>();
    }

    /**
     * Re-assemble a {@link ProjectExport} from this import so the bundle can be
     * serialized again byte-identically (the basis of the R3 round-trip).
     */
    public ProjectExport toExport() {
        return new ProjectExport(
                project, catalogs, tags, entityTags,
                tasks, workflows, workflowNodes, workflowEdges,
                taskSlugs, workflowSlugs, taskDatasourceCodes, taskTargetDatasourceCodes,
                taskDeclaredSchema, taskDeclaredColumnEdges);
    }

    /** Builder pattern for incremental assembly by mappers. */
    public static class Builder {
        private Project project;
        private final List<CatalogNode> catalogs = new ArrayList<>();
        private final List<Tag> tags = new ArrayList<>();
        private final List<EntityTag> entityTags = new ArrayList<>();
        private final List<TaskDef> tasks = new ArrayList<>();
        private final List<WorkflowDef> workflows = new ArrayList<>();
        private final List<WorkflowNode> workflowNodes = new ArrayList<>();
        private final List<WorkflowEdge> workflowEdges = new ArrayList<>();
        private final Map<Long, String> taskSlugs = new LinkedHashMap<>();
        private final Map<Long, String> workflowSlugs = new LinkedHashMap<>();
        private final Map<Long, String> taskDatasourceCodes = new LinkedHashMap<>();
        private final Map<Long, String> taskTargetDatasourceCodes = new LinkedHashMap<>();
        private final Map<Long, java.util.Map<String, java.util.List<com.dataweave.master.filecontract.dto.ColumnSchemaDecl>>>
                taskDeclaredSchema = new LinkedHashMap<>();
        private final Map<Long, java.util.List<Map<String, String>>>
                taskDeclaredColumnEdges = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();

        public Builder project(Project v) { this.project = v; return this; }
        public Builder addCatalog(CatalogNode v) { catalogs.add(v); return this; }
        public Builder addTag(Tag v) { tags.add(v); return this; }
        public Builder addTags(List<Tag> v) { tags.addAll(v); return this; }
        public Builder addEntityTag(EntityTag v) { entityTags.add(v); return this; }
        public Builder addTask(TaskDef v) { tasks.add(v); return this; }
        public Builder addWorkflow(WorkflowDef v) { workflows.add(v); return this; }
        public Builder addWorkflowNode(WorkflowNode v) { workflowNodes.add(v); return this; }
        public Builder addWorkflowEdge(WorkflowEdge v) { workflowEdges.add(v); return this; }
        public Builder taskSlug(Long id, String slug) { if (id != null) taskSlugs.put(id, slug); return this; }
        public Builder workflowSlug(Long id, String slug) { if (id != null) workflowSlugs.put(id, slug); return this; }
        public Builder taskDatasourceCode(Long id, String code) {
            if (id != null && code != null) taskDatasourceCodes.put(id, code);
            return this;
        }
        public Builder taskTargetDatasourceCode(Long id, String code) {
            if (id != null && code != null) taskTargetDatasourceCodes.put(id, code);
            return this;
        }
        public Builder taskDeclaredSchema(Long id,
                java.util.Map<String, java.util.List<com.dataweave.master.filecontract.dto.ColumnSchemaDecl>> schema) {
            if (id != null && schema != null && !schema.isEmpty()) taskDeclaredSchema.put(id, schema);
            return this;
        }
        public Builder taskDeclaredColumnEdges(Long id, java.util.List<Map<String, String>> edges) {
            if (id != null && edges != null && !edges.isEmpty()) taskDeclaredColumnEdges.put(id, edges);
            return this;
        }
        public Builder addWarning(String w) { warnings.add(w); return this; }

        public ProjectImport build() {
            return new ProjectImport(project, catalogs, tags, entityTags,
                    tasks, workflows, workflowNodes, workflowEdges,
                    taskSlugs, workflowSlugs, taskDatasourceCodes, taskTargetDatasourceCodes,
                    taskDeclaredSchema, taskDeclaredColumnEdges,
                    warnings);
        }
    }
}
