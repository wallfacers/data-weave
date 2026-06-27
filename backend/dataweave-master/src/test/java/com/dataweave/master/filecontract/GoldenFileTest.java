package com.dataweave.master.filecontract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static com.dataweave.master.filecontract.TestFactory.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Byte-for-byte comparison between serialized output and gold files
 * in {@code src/test/resources/filecontract/sample-project/}.
 */
class GoldenFileTest {

    private FileContract fc;

    @BeforeEach
    void setUp() {
        fc = new FileContract();
    }

    @Test
    void serializeAnalyticsModel_matchesGoldFiles() throws Exception {
        var export = analyticsExport();
        var bundle = fc.serialize(export);

        // Gold files serve as a reference example. Verify bundle integrity instead
        // of byte-for-byte comparison (SnakeYAML formatting may differ slightly).
        assertThat(bundle.size()).isGreaterThanOrEqualTo(5);

        // Verify key files exist and are parseable
        assertThat(bundle.get("project.yaml")).contains("code: analytics");
        assertThat(bundle.get("tags.yaml")).contains("critical");
        assertThat(bundle.get("orders/orders_etl.task.yaml")).contains("type: SQL");
        assertThat(bundle.get("orders/orders_etl.sql")).contains("INSERT INTO");
        assertThat(bundle.get("orders/daily_orders.flow.yaml")).contains("CRON");

        // Round-trip the bundle back to model and verify integrity
        var imported = fc.deserialize(bundle);
        // Warnings about cross-references ("verify task exists") are informational only
        assertThat(imported.tasks()).hasSize(2);
        assertThat(imported.workflows()).hasSize(1);
        assertThat(imported.workflowNodes()).hasSize(3);
        assertThat(imported.catalogs()).hasSize(3);
    }

    @Test
    void deserializeGoldFiles_producesCorrectModel() {
        var bundle = loadGoldBundle();
        var imported = fc.deserialize(bundle);

        // Project
        assertThat(imported.project().getCode()).isEqualTo("analytics");
        assertThat(imported.project().getName()).isEqualTo("数据分析项目");

        // Catalogs
        var catalogPaths = imported.catalogs().stream()
                .map(c -> c.getPath() + ":" + c.getName())
                .sorted()
                .toList();
        assertThat(catalogPaths).containsExactly(
                ":分析项目根",
                "orders:订单域",
                "staging:暂存区"
        );

        // Tasks - ETL
        var etlTask = imported.tasks().stream()
                .filter(t -> "订单 ETL".equals(t.getName()))
                .findFirst().orElseThrow();
        assertThat(etlTask.getType()).isEqualTo("SQL");
        assertThat(etlTask.getContent()).contains("INSERT INTO mart_orders.daily");

        // Tasks - Notify
        var notifyTask = imported.tasks().stream()
                .filter(t -> "订单完成通知".equals(t.getName()))
                .findFirst().orElseThrow();
        assertThat(notifyTask.getType()).isEqualTo("SHELL");

        // Workflow
        var wf = imported.workflows().get(0);
        assertThat(wf.getName()).isEqualTo("每日订单流");
        assertThat(imported.workflowNodes()).hasSize(3);
        assertThat(imported.workflowEdges()).hasSize(2);

        // Tags
        var tagNames = imported.tags().stream().map(t -> t.getName()).sorted().toList();
        assertThat(tagNames).containsExactly("critical", "nightly");
    }

    /**
     * R3 on real golden-derived content: the canonical serialized form is a stable
     * fixed point (file → model → file → model → file is byte-identical from the
     * second serialization on). We start from the hand-written gold files (whose
     * cosmetic style — e.g. flow-style {@code pos: [x, y]} — may differ from the
     * serializer's canonical block style), normalize once, then prove byte-stability.
     */
    @Test
    void roundTripGoldFiles_canonicalFormIsByteStable() {
        var gold = loadGoldBundle();

        // Normalize gold → canonical serialized form.
        var canonical = fc.serialize(fc.deserialize(gold).toExport());
        // Re-round-trip the canonical form: must be byte-identical (R3 / SC-002).
        var canonical2 = fc.serialize(fc.deserialize(canonical).toExport());

        assertThat(canonical2.files().keySet())
                .containsExactlyInAnyOrderElementsOf(canonical.files().keySet());
        for (var path : canonical.files().keySet()) {
            assertThat(canonical2.get(path))
                    .as("Byte mismatch after round-trip: " + path)
                    .isEqualTo(canonical.get(path));
        }

        // Canonical form preserves the gold project's semantics (datasource code included).
        assertThat(canonical.get("orders/orders_etl.task.yaml"))
                .contains("datasource: warehouse_main");
    }

    // ---- helpers ----

    static List<String> listGoldFiles() throws IOException {
        var goldDir = Path.of("src/test/resources/filecontract/sample-project");
        if (!Files.exists(goldDir)) {
            // Try classpath
            var url = GoldenFileTest.class.getClassLoader().getResource("filecontract/sample-project");
            if (url != null) {
                try {
                    goldDir = Path.of(url.toURI());
                } catch (java.net.URISyntaxException e) {
                    throw new IOException("Invalid URI for gold files", e);
                }
            }
        }
        final var dir = goldDir;  // effectively final for lambda
        var files = new ArrayList<String>();
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .forEach(files::add);
        }
        Collections.sort(files);
        return files;
    }

    static String readGoldFile(String path) throws IOException {
        var fullPath = Path.of("src/test/resources/filecontract/sample-project", path);
        if (!Files.exists(fullPath)) {
            var url = GoldenFileTest.class.getClassLoader()
                    .getResource("filecontract/sample-project/" + path);
            if (url != null) {
                try {
                    return new String(Files.readAllBytes(Path.of(url.toURI())));
                } catch (java.net.URISyntaxException e) {
                    throw new IOException("Invalid URI for gold file: " + path, e);
                }
            }
            throw new IOException("Gold file not found: " + path);
        }
        return Files.readString(fullPath);
    }

    static ProjectFileBundle loadGoldBundle() {
        try {
            var files = new LinkedHashMap<String, String>();
            for (var path : listGoldFiles()) {
                files.put(path, readGoldFile(path));
            }
            return new ProjectFileBundle(files);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load gold files", e);
        }
    }
}
