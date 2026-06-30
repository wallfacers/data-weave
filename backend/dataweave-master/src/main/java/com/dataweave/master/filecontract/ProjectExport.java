package com.dataweave.master.filecontract;

import com.dataweave.master.domain.*;

import java.util.List;
import java.util.Map;

/**
 * Domain aggregate ready for serialization into a ProjectFileBundle.
 * Assembled by the caller (C layer) from repositories.
 *
 * <p>Because the domain {@link TaskDef} carries only a numeric {@code datasourceId}
 * (a foreign key), the logical datasource <em>code</em> that belongs in the file travels
 * alongside in {@code taskDatasourceCodes}/{@code taskTargetDatasourceCodes} (FR-009: the
 * Long↔code resolution is C/environment's job; B faithfully round-trips the code).
 *
 * @param taskSlugs                taskId → file slug (determines {@code <slug>.task.yaml})
 * @param workflowSlugs            workflowId → file slug (determines {@code <slug>.flow.yaml})
 * @param taskDatasourceCodes      taskId → datasource logical code (nullable per task)
 * @param taskTargetDatasourceCodes taskId → target datasource logical code (nullable per task)
 */
public record ProjectExport(
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
        Map<Long, java.util.List<Map<String, String>>> taskDeclaredColumnEdges
) {
    /** Convenience constructor with slug + datasource maps but no schema maps. */
    public ProjectExport(
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
            Map<Long, String> taskTargetDatasourceCodes
    ) {
        this(project, catalogs, tags, entityTags, tasks, workflows,
                workflowNodes, workflowEdges, taskSlugs, workflowSlugs,
                taskDatasourceCodes, taskTargetDatasourceCodes, Map.of(), Map.of());
    }

    /** Convenience constructor with slug maps but no datasource codes. */
    public ProjectExport(
            Project project,
            List<CatalogNode> catalogs,
            List<Tag> tags,
            List<EntityTag> entityTags,
            List<TaskDef> tasks,
            List<WorkflowDef> workflows,
            List<WorkflowNode> workflowNodes,
            List<WorkflowEdge> workflowEdges,
            Map<Long, String> taskSlugs,
            Map<Long, String> workflowSlugs
    ) {
        this(project, catalogs, tags, entityTags, tasks, workflows,
                workflowNodes, workflowEdges, taskSlugs, workflowSlugs, Map.of(), Map.of(),
                Map.of(), Map.of());
    }

    /** Convenience constructor with empty slug + datasource maps. */
    public ProjectExport(
            Project project,
            List<CatalogNode> catalogs,
            List<Tag> tags,
            List<EntityTag> entityTags,
            List<TaskDef> tasks,
            List<WorkflowDef> workflows,
            List<WorkflowNode> workflowNodes,
            List<WorkflowEdge> workflowEdges
    ) {
        this(project, catalogs, tags, entityTags, tasks, workflows,
                workflowNodes, workflowEdges, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of());
    }
}
