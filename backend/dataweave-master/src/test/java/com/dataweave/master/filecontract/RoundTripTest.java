package com.dataweave.master.filecontract;

import com.dataweave.master.filecontract.error.FileContractException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.dataweave.master.filecontract.TestFactory.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2/R3/R6: Round-trip integrity — model→file→model semantic equivalence and
 * file→model→file byte stability.
 */
class RoundTripTest {

    private FileContract fc;

    @BeforeEach
    void setUp() {
        fc = new FileContract();
    }

    // ---- R2: model → file → model semantic equivalence ----

    @Test
    void modelToFileToModel_preservesProject() {
        var export = analyticsExport();
        var bundle = fc.serialize(export);
        var imported = fc.deserialize(bundle);

        assertThat(imported.project().getCode()).isEqualTo("analytics");
        assertThat(imported.project().getName()).isEqualTo("数据分析项目");
    }

    @Test
    void modelToFileToModel_preservesCatalogTree() {
        var export = analyticsExport();
        var bundle = fc.serialize(export);
        var imported = fc.deserialize(bundle);

        var names = imported.catalogs().stream()
                .map(c -> c.getPath() + ":" + c.getName())
                .sorted()
                .toList();
        assertThat(names).contains(
                "orders:订单域",
                "staging:暂存区"
        );
    }

    @Test
    void modelToFileToModel_preservesTaskFields() {
        var export = analyticsExport();
        var bundle = fc.serialize(export);
        var imported = fc.deserialize(bundle);

        var etlTask = imported.tasks().stream()
                .filter(t -> "订单 ETL".equals(t.getName()))
                .findFirst().orElseThrow();

        assertThat(etlTask.getType()).isEqualTo("SQL");
        assertThat(etlTask.getDescription()).isEqualTo("每日订单宽表抽取");
        assertThat(etlTask.getPriority()).isEqualTo(5);
        assertThat(etlTask.getTimeoutSec()).isEqualTo(600);
        assertThat(etlTask.getRetryMax()).isEqualTo(2);
        assertThat(etlTask.getFrozen()).isEqualTo(0);
    }

    @Test
    void modelToFileToModel_preservesScriptContent() { // R6
        var export = analyticsExport();
        var bundle = fc.serialize(export);
        var imported = fc.deserialize(bundle);

        var etlTask = imported.tasks().stream()
                .filter(t -> "订单 ETL".equals(t.getName()))
                .findFirst().orElseThrow();

        assertThat(etlTask.getContent()).contains("INSERT INTO mart_orders.daily");
        assertThat(etlTask.getContent()).contains("GROUP BY order_date;");
    }

    @Test
    void modelToFileToModel_preservesParams() {
        var export = analyticsExport();
        var bundle = fc.serialize(export);
        var imported = fc.deserialize(bundle);

        var etlTask = imported.tasks().stream()
                .filter(t -> "订单 ETL".equals(t.getName()))
                .findFirst().orElseThrow();

        // paramsJson is canonical JSON with sorted keys
        assertThat(etlTask.getParamsJson()).contains("bizDate");
        assertThat(etlTask.getParamsJson()).contains("mode");
    }

    @Test
    void modelToFileToModel_preservesWorkflowDAG() {
        var export = analyticsExport();
        var bundle = fc.serialize(export);
        var imported = fc.deserialize(bundle);

        var wf = imported.workflows().stream()
                .filter(w -> "每日订单流".equals(w.getName()))
                .findFirst().orElseThrow();

        assertThat(wf.getScheduleType()).isEqualTo("CRON");
        assertThat(wf.getCron()).isEqualTo("0 0 2 * * ?");
        assertThat(wf.getPriority()).isEqualTo(5);
        assertThat(wf.getTimeoutSec()).isEqualTo(3600);
        assertThat(wf.getPreemptible()).isEqualTo(1);

        // Nodes
        var nodeKeys = imported.workflowNodes().stream()
                .map(n -> n.getNodeKey())
                .toList();
        assertThat(nodeKeys).contains("n_etl", "n_notify", "start");

        // Edges
        assertThat(imported.workflowEdges()).hasSize(2);
    }

    @Test
    void modelToFileToModel_preservesTags() {
        var export = analyticsExport();
        var bundle = fc.serialize(export);
        var imported = fc.deserialize(bundle);

        var tagNames = imported.tags().stream()
                .map(t -> t.getName())
                .sorted()
                .toList();
        assertThat(tagNames).contains("critical", "nightly");
    }

    @Test
    void modelToFileToModel_nullOptionalFields() {
        var export = minimalExport();
        var bundle = fc.serialize(export);
        var imported = fc.deserialize(bundle);

        var task = imported.tasks().get(0);
        assertThat(task.getDescription()).isNull();
        assertThat(task.getPriority()).isNull();
        assertThat(task.getTimeoutSec()).isNull();
        assertThat(task.getRetryMax()).isNull();
        assertThat(task.getFrozen()).isEqualTo(0);  // default
    }

    // ---- R3: file → model → file byte stability (FR-011② / SC-002) ----
    // Identity is path/slug-based (FR-007); deserialize assigns deterministic synthetic
    // ids and exposes ProjectImport.toExport(), so a re-serialize reproduces the bundle
    // byte-for-byte. No C-layer id plumbing required.

    @Test
    void fileToModelToFile_byteStable() {
        var export = analyticsExport();
        var bundle1 = fc.serialize(export);

        // datasource logical code must actually be emitted (FR-009, B3 regression)
        assertThat(bundle1.get("orders/orders_etl.task.yaml"))
                .contains("datasource: warehouse_main")
                .contains("targetDatasource: mart_orders");

        var imported = fc.deserialize(bundle1);
        var bundle2 = fc.serialize(imported.toExport());

        // Same file set
        assertThat(bundle2.files().keySet())
                .containsExactlyInAnyOrderElementsOf(bundle1.files().keySet());

        // Each file byte-identical
        for (var path : bundle1.files().keySet()) {
            assertThat(bundle2.get(path))
                    .as("file: " + path)
                    .isEqualTo(bundle1.get(path));
        }
    }

    @Test
    void fileToModelToFile_preservesDatasourceAndCatalogAndEdges() {
        var bundle1 = fc.serialize(analyticsExport());
        var imported = fc.deserialize(bundle1);

        // datasource code round-trips on the import aggregate (FR-009)
        var etl = imported.tasks().stream()
                .filter(t -> "订单 ETL".equals(t.getName())).findFirst().orElseThrow();
        assertThat(imported.taskDatasourceCodes().get(etl.getId())).isEqualTo("warehouse_main");

        // catalog membership survives the model boundary (FR-008): etl lives under "orders"
        var ordersCatalogId = imported.catalogs().stream()
                .filter(c -> "orders".equals(c.getPath())).findFirst().orElseThrow().getId();
        assertThat(etl.getCatalogNodeId()).isEqualTo(ordersCatalogId);

        // edge topology survives: every edge connects two real nodes of its workflow
        var nodeIds = imported.workflowNodes().stream().map(n -> n.getId()).toList();
        assertThat(imported.workflowEdges()).allSatisfy(e -> {
            assertThat(nodeIds).contains(e.getFromNodeId());
            assertThat(nodeIds).contains(e.getToNodeId());
        });
    }
}
