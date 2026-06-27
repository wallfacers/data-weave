package com.dataweave.master.filecontract;

import com.dataweave.master.domain.TaskDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;

import static com.dataweave.master.filecontract.TestFactory.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5: Diff locality — changing a single field affects exactly 1 file
 * in exactly 1 place.
 */
class DiffLocalityTest {

    private FileContract fc;

    @BeforeEach
    void setUp() {
        fc = new FileContract();
    }

    @Test
    void changeTaskDescription_changesOnlyTaskYaml() {
        var export1 = analyticsExport();
        var bundle1 = fc.serialize(export1);

        // Change description of orders_etl task
        var etlTask = export1.tasks().stream()
                .filter(t -> "订单 ETL".equals(t.getName()))
                .findFirst().orElseThrow();
        etlTask.setDescription("修改后的描述");

        var bundle2 = fc.serialize(export1);

        // Count changed files
        int changed = 0;
        for (var path : bundle1.files().keySet()) {
            if (!Objects.equals(bundle1.get(path), bundle2.get(path))) {
                changed++;
                // Should only be the task yaml file
                assertThat(path).endsWith("orders_etl.task.yaml");
            }
        }
        assertThat(changed).isEqualTo(1);
    }

    @Test
    void changeTaskPriority_changesOnlyTaskYaml() {
        var export1 = analyticsExport();
        var bundle1 = fc.serialize(export1);

        // Change priority
        var etlTask = export1.tasks().stream()
                .filter(t -> "订单 ETL".equals(t.getName()))
                .findFirst().orElseThrow();
        etlTask.setPriority(9);

        var bundle2 = fc.serialize(export1);

        int changed = 0;
        for (var path : bundle1.files().keySet()) {
            if (!Objects.equals(bundle1.get(path), bundle2.get(path))) {
                changed++;
                assertThat(path).endsWith("orders_etl.task.yaml");
            }
        }
        assertThat(changed).isEqualTo(1);
    }

    @Test
    void changeCatalogSortOrder_changesOnlyFolderYaml() {
        var export1 = analyticsExport();
        var bundle1 = fc.serialize(export1);

        // Change sortOrder of orders catalog
        var ordersNode = export1.catalogs().stream()
                .filter(c -> "订单域".equals(c.getName()))
                .findFirst().orElseThrow();
        ordersNode.setSortOrder(99);

        var bundle2 = fc.serialize(export1);

        int changed = 0;
        for (var path : bundle1.files().keySet()) {
            if (!Objects.equals(bundle1.get(path), bundle2.get(path))) {
                changed++;
                assertThat(path).contains("_folder.yaml");
            }
        }
        assertThat(changed).isEqualTo(1);
    }

    @Test
    void addTask_addsExactlyTwoFiles() {
        var export1 = analyticsExport();
        var bundle1 = fc.serialize(export1);

        // Add a new task in orders/
        var newTask = new TaskDef();
        newTask.setId(999L);
        newTask.setName("new_echo");
        newTask.setType("ECHO");
        newTask.setContent("echo hello");
        newTask.setCatalogNodeId(export1.catalogs().stream()
                .filter(c -> "订单域".equals(c.getName()))
                .findFirst().orElseThrow().getId());
        var newTasks = new ArrayList<>(export1.tasks());
        newTasks.add(newTask);
        var slugMap = new java.util.HashMap<>(export1.taskSlugs());
        slugMap.put(999L, "new_echo");
        var export2 = new ProjectExport(export1.project(), export1.catalogs(),
                export1.tags(), export1.entityTags(), newTasks,
                export1.workflows(), export1.workflowNodes(), export1.workflowEdges(),
                slugMap, export1.workflowSlugs());

        var bundle2 = fc.serialize(export2);

        // New files: <slug>.task.yaml + <slug>.txt
        var newFiles = new HashSet<>(bundle2.files().keySet());
        newFiles.removeAll(bundle1.files().keySet());
        assertThat(newFiles).hasSize(2);
        assertThat(newFiles.stream().anyMatch(f -> f.endsWith(".task.yaml"))).isTrue();
        assertThat(newFiles.stream().anyMatch(f -> f.endsWith(".txt"))).isTrue();
    }

    @Test
    void identicalDefinition_producesZeroDiff() {
        var export = analyticsExport();
        var bundle1 = fc.serialize(export);
        var bundle2 = fc.serialize(export);

        for (var path : bundle1.files().keySet()) {
            assertThat(bundle2.get(path))
                    .as("File " + path + " should be identical")
                    .isEqualTo(bundle1.get(path));
        }
    }
}
