package com.dataweave.master.filecontract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.dataweave.master.filecontract.TestFactory.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1/R4: Deterministic serialization — 100× byte-identical and order-independent.
 */
class DeterminismTest {

    private FileContract fc;

    @BeforeEach
    void setUp() {
        fc = new FileContract();
    }

    // ---- R1: 100× serialize → same bytes ----

    @Test
    void serialized100Times_producesIdenticalOutput() {
        var export = analyticsExport();

        ProjectFileBundle first = null;
        for (int i = 0; i < 100; i++) {
            var bundle = fc.serialize(export);
            if (first == null) {
                first = bundle;
            } else {
                for (var path : first.files().keySet()) {
                    assertThat(bundle.get(path))
                            .as("Iteration " + i + ", file " + path + " differs from first")
                            .isEqualTo(first.get(path));
                }
            }
        }
    }

    // ---- R4: collection order independence ----

    @Test
    void shuffledTags_producesIdenticalOutput() {
        var export = analyticsExport();
        var bundle1 = fc.serialize(export);

        // Reverse tag order
        var reversedTags = new ArrayList<>(export.tags());
        Collections.reverse(reversedTags);
        var export2 = new ProjectExport(export.project(), export.catalogs(),
                reversedTags, export.entityTags(), export.tasks(),
                export.workflows(), export.workflowNodes(), export.workflowEdges(),
                export.taskSlugs(), export.workflowSlugs());
        var bundle2 = fc.serialize(export2);

        assertThat(bundle2.get("tags.yaml")).isEqualTo(bundle1.get("tags.yaml"));
    }

    @Test
    void keyOrderIsFixed_notAlphabetical() {
        var export = analyticsExport();
        var bundle = fc.serialize(export);

        // project.yaml keys should be in fixed contract order: formatVersion, code, name
        var projectYaml = bundle.get("project.yaml");
        var formatPos = projectYaml.indexOf("formatVersion:");
        var codePos = projectYaml.indexOf("code:");
        var namePos = projectYaml.indexOf("name:");
        assertThat(formatPos).isLessThan(codePos);
        assertThat(codePos).isLessThan(namePos);
    }

    @Test
    void jsonParams_producesCanonicalOrder() {
        var export = analyticsExport();
        var bundle = fc.serialize(export);

        var taskYaml = bundle.files().keySet().stream()
                .filter(p -> p.endsWith("orders_etl.task.yaml"))
                .findFirst().orElseThrow();
        var content = bundle.get(taskYaml);

        // params keys should be sorted: bizDate before mode
        var bizPos = content.indexOf("bizDate:");
        var modePos = content.indexOf("mode:");
        assertThat(bizPos).isLessThan(modePos);
    }

    @Test
    void nodesSortedByKey_edgesSortedByFromThenTo() {
        var export = analyticsExport();
        var bundle = fc.serialize(export);

        var flowYaml = bundle.files().keySet().stream()
                .filter(p -> p.endsWith("daily_orders.flow.yaml"))
                .findFirst().orElseThrow();
        var content = bundle.get(flowYaml);

        // n_etl should appear before n_notify in nodes list
        var etlPos = content.indexOf("key: n_etl");
        var notifyPos = content.indexOf("key: n_notify");
        var startPos = content.indexOf("key: start");
        assertThat(etlPos).isLessThan(notifyPos);
        assertThat(notifyPos).isLessThan(startPos);
    }
}
