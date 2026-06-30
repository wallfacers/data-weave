package com.dataweave.master.filecontract.mapping;

import com.dataweave.master.filecontract.dto.ColumnSchemaDecl;
import com.dataweave.master.filecontract.dto.TaskDoc;
import com.dataweave.master.filecontract.yaml.DeterministicYaml;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskMapper 024 声明列元数据解析 + round-trip 测试。
 */
class TaskMapperTest {

    private final TaskMapper mapper = new TaskMapper(new DeterministicYaml(), new ObjectMapper());

    // ---- schema 块解析 ----

    @Test
    void parseSchemaBlockWithSingleTable() {
        String yaml = """
                formatVersion: 1
                name: etl_test
                type: SQL
                schema:
                  orders:
                    - { name: order_id, type: BIGINT }
                    - { name: amount, type: "DECIMAL(18,2)" }
                """;
        TaskDoc doc = mapper.fromYaml(yaml, "test.task.yaml");
        assertThat(doc.declaredSchema()).isNotNull();
        assertThat(doc.declaredSchema()).containsKey("orders");
        var cols = doc.declaredSchema().get("orders");
        assertThat(cols).hasSize(2);
        assertThat(cols.get(0).name()).isEqualTo("order_id");
        assertThat(cols.get(0).type()).isEqualTo("BIGINT");
        assertThat(cols.get(1).name()).isEqualTo("amount");
        assertThat(cols.get(1).type()).isEqualTo("DECIMAL(18,2)");
    }

    @Test
    void parseSchemaBlockWithMultipleTables() {
        String yaml = """
                formatVersion: 1
                name: etl_multi
                type: SQL
                schema:
                  src_table:
                    - { name: id, type: INT }
                    - { name: val, type: VARCHAR }
                  dst_table:
                    - { name: result, type: DOUBLE }
                """;
        TaskDoc doc = mapper.fromYaml(yaml, "test.task.yaml");
        assertThat(doc.declaredSchema()).hasSize(2);
        assertThat(doc.declaredSchema().get("src_table")).hasSize(2);
        assertThat(doc.declaredSchema().get("dst_table")).hasSize(1);
    }

    @Test
    void parseSchemaBlockMissing() {
        String yaml = """
                formatVersion: 1
                name: no_schema
                type: SHELL
                """;
        TaskDoc doc = mapper.fromYaml(yaml, "test.task.yaml");
        assertThat(doc.declaredSchema()).isNull();
        assertThat(doc.declaredColumnLineage()).isNull();
    }

    // ---- columnLineage 块解析 ----

    @Test
    void parseColumnLineageBlock() {
        String yaml = """
                formatVersion: 1
                name: etl_lineage
                type: SQL
                columnLineage:
                  - { from: orders.amount, to: dwd.total }
                  - { from: orders.order_id, to: dwd.order_id }
                """;
        TaskDoc doc = mapper.fromYaml(yaml, "test.task.yaml");
        assertThat(doc.declaredColumnLineage()).isNotNull().hasSize(2);
        assertThat(doc.declaredColumnLineage().get(0)).containsEntry("from", "orders.amount");
        assertThat(doc.declaredColumnLineage().get(0)).containsEntry("to", "dwd.total");
    }

    @Test
    void parseBothBlocks() {
        String yaml = """
                formatVersion: 1
                name: etl_full
                type: SQL
                schema:
                  orders: [{name: id, type: INT}]
                columnLineage:
                  - { from: orders.id, to: dwd.id }
                """;
        TaskDoc doc = mapper.fromYaml(yaml, "test.task.yaml");
        assertThat(doc.declaredSchema()).isNotNull();
        assertThat(doc.declaredColumnLineage()).isNotNull();
    }

    // ---- round-trip (SC-005) ----

    @Test
    void schemaRoundTripDoesNotLoseFields() {
        String yaml = """
                formatVersion: 1
                name: roundtrip
                type: SQL
                schema:
                  tbl:
                    - { name: col_a, type: BIGINT }
                    - { name: col_b, type: VARCHAR(255) }
                columnLineage:
                  - { from: tbl.col_a, to: dst.col_a }
                """;
        TaskDoc doc1 = mapper.fromYaml(yaml, "test.task.yaml");
        String serialized = mapper.serialize(doc1);
        TaskDoc doc2 = mapper.fromYaml(serialized, "test.task.yaml");

        assertThat(doc2.declaredSchema()).isNotNull();
        assertThat(doc2.declaredSchema()).containsKey("tbl");
        assertThat(doc2.declaredSchema().get("tbl")).hasSize(2);
        assertThat(doc2.declaredColumnLineage()).hasSize(1);
    }

    // ---- 边缘 ----

    @Test
    void emptySchemaBlockIsNull() {
        String yaml = """
                formatVersion: 1
                name: empty_schema
                type: SQL
                schema: {}
                """;
        TaskDoc doc = mapper.fromYaml(yaml, "test.task.yaml");
        assertThat(doc.declaredSchema()).isNull();
    }

    @Test
    void invalidSchemaBlockDoesNotThrow() {
        String yaml = """
                formatVersion: 1
                name: bad_schema
                type: SQL
                schema: "not a map"
                """;
        TaskDoc doc = mapper.fromYaml(yaml, "test.task.yaml");
        assertThat(doc.declaredSchema()).isNull(); // 降级，不抛
    }

    @Test
    void invalidColumnLineageBlockDoesNotThrow() {
        String yaml = """
                formatVersion: 1
                name: bad_lineage
                type: SQL
                columnLineage: "not a list"
                """;
        TaskDoc doc = mapper.fromYaml(yaml, "test.task.yaml");
        assertThat(doc.declaredColumnLineage()).isNull(); // 降级，不抛
    }
}
