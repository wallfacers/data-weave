package com.dataweave.master.filecontract;

import com.dataweave.master.filecontract.error.FileContractException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-015/FR-016: Error handling and forward compatibility.
 */
class ErrorHandlingTest {

    private FileContract fc;

    @BeforeEach
    void setUp() {
        fc = new FileContract();
    }

    // ---- Missing required fields ----

    @Test
    void missingProjectYaml_throwsFileContractException() {
        var files = new LinkedHashMap<String, String>();
        files.put("tags.yaml", "tags: []\n");
        var bundle = new ProjectFileBundle(files);

        assertThatThrownBy(() -> fc.deserialize(bundle))
                .isInstanceOf(FileContractException.class)
                .hasMessageContaining("project.yaml");
    }

    @Test
    void missingTaskName_returnsWarning() {
        var bundle = buildBundleWithTask("""
                formatVersion: 1
                type: SQL
                """);
        var imported = fc.deserialize(bundle);
        assertThat(imported.warnings()).isNotEmpty();
        assertThat(imported.warnings().get(0)).contains("name");
    }

    @Test
    void missingTaskType_returnsWarning() {
        var bundle = buildBundleWithTask("""
                formatVersion: 1
                name: 无类型任务
                """);
        var imported = fc.deserialize(bundle);
        assertThat(imported.warnings()).isNotEmpty();
        assertThat(imported.warnings().get(0)).contains("type");
    }

    // ---- Type mismatch ----

    @Test
    void priorityIsString_returnsLocatedError() {
        var bundle = buildBundleWithTask("""
                formatVersion: 1
                name: 坏任务
                type: SQL
                priority: 高
                """);
        var imported = fc.deserialize(bundle);
        // FR-015: a wrong-typed field is a located error (file + field + expected type),
        // NOT a silent null-coercion. The malformed task is rejected, not half-accepted.
        assertThat(imported.warnings()).anySatisfy(w ->
                assertThat(w).contains("priority").contains("integer"));
        assertThat(imported.tasks()).isEmpty();
    }

    // ---- Dangling references in workflow ----

    @Test
    void edgeReferencesNonExistentNode_returnsWarning() {
        var bundle = buildBundleWithWorkflow("""
                formatVersion: 1
                name: 坏工作流
                schedule:
                  type: MANUAL
                nodes:
                  - key: n1
                    type: TASK
                    task: test
                edges:
                  - from: n1
                    to: ghost
                """);
        var imported = fc.deserialize(bundle);
        assertThat(imported.warnings()).isNotEmpty();
        var combined = String.join("\n", imported.warnings());
        assertThat(combined).contains("ghost");
    }

    // ---- TASK/VIRTUAL node validation ----

    @Test
    void taskNodeWithoutTaskField_returnsWarning() {
        var bundle = buildBundleWithWorkflow("""
                formatVersion: 1
                name: 坏工作流
                schedule:
                  type: MANUAL
                nodes:
                  - key: n1
                    type: TASK
                edges: []
                """);
        var imported = fc.deserialize(bundle);
        assertThat(imported.warnings()).isNotEmpty();
        var combined = String.join("\n", imported.warnings());
        assertThat(combined).contains("TASK");
    }

    @Test
    void virtualNodeWithTaskField_returnsWarning() {
        var bundle = buildBundleWithWorkflow("""
                formatVersion: 1
                name: 坏工作流
                schedule:
                  type: MANUAL
                nodes:
                  - key: v1
                    type: VIRTUAL
                    task: something
                edges: []
                """);
        var imported = fc.deserialize(bundle);
        assertThat(imported.warnings()).isNotEmpty();
        var combined = String.join("\n", imported.warnings());
        assertThat(combined).contains("VIRTUAL");
    }

    // ---- Missing schedule ----

    @Test
    void workflowMissingSchedule_returnsWarning() {
        var bundle = buildBundleWithWorkflow("""
                formatVersion: 1
                name: 无调度工作流
                nodes:
                  - key: n1
                    type: VIRTUAL
                edges: []
                """);
        var imported = fc.deserialize(bundle);
        assertThat(imported.warnings()).isNotEmpty();
        assertThat(imported.warnings().get(0)).contains("schedule");
    }

    // ---- Unknown fields (FR-016 forward compat) ----

    @Test
    void unknownFields_areIgnored_notErrors() {
        var bundle = buildBundleWithTask("""
                formatVersion: 1
                name: 未来任务
                type: SQL
                futureField: someValue
                anotherNewField: 42
                nestedUnknown:
                  foo: bar
                """);
        var imported = fc.deserialize(bundle);
        // No exception thrown; task parsed correctly
        var task = imported.tasks().get(0);
        assertThat(task.getName()).isEqualTo("未来任务");
        assertThat(task.getType()).isEqualTo("SQL");
    }

    @Test
    void unknownTaskType_isAccepted() {
        var bundle = buildBundleWithTask("""
                formatVersion: 1
                name: 未来类型
                type: KUBERNETES_POD
                """);
        var imported = fc.deserialize(bundle);
        var task = imported.tasks().get(0);
        // type is an open string — KUBERNETES_POD is accepted
        assertThat(task.getType()).isEqualTo("KUBERNETES_POD");
    }

    @Test
    void higherFormatVersion_notFatal() {
        var bundle = buildBundleWithTask("""
                formatVersion: 99
                name: 未来格式版本
                type: SQL
                """);
        // Should not throw — just continues
        var imported = fc.deserialize(bundle);
        var task = imported.tasks().get(0);
        assertThat(task.getName()).isEqualTo("未来格式版本");
    }

    // ---- helpers ----

    private ProjectFileBundle buildBundleWithTask(String taskYaml) {
        var files = new LinkedHashMap<String, String>();
        files.put("project.yaml", """
                formatVersion: 1
                code: test
                name: 测试项目
                """);
        files.put("tags.yaml", """
                formatVersion: 1
                tags: []
                """);
        files.put("test.task.yaml", taskYaml);
        return new ProjectFileBundle(files);
    }

    private ProjectFileBundle buildBundleWithWorkflow(String flowYaml) {
        var files = new LinkedHashMap<String, String>();
        files.put("project.yaml", """
                formatVersion: 1
                code: test
                name: 测试项目
                """);
        files.put("tags.yaml", """
                formatVersion: 1
                tags: []
                """);
        files.put("test.task.yaml", """
                formatVersion: 1
                name: test task
                type: ECHO
                """);
        files.put("test.flow.yaml", flowYaml);
        return new ProjectFileBundle(files);
    }
}
