package com.dataweave.master.application.lineage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import com.dataweave.master.application.SqlTableExtractor;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.Source;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link LineageEdgeAssembler} 的 A×B 交叉校验单测（迁移自原 TaskServiceLineageTest）。
 * 用真实 {@link SqlTableExtractor} 解析 SQL，断言产出的 {@link IoEdge} 来源/可信度。
 * datasource 传 null（resolveCoord 返回降级身份，不触 JdbcTemplate）。
 */
class LineageEdgeAssemblerTest {

    private final LineageEdgeAssembler assembler =
            new LineageEdgeAssembler(new SqlTableExtractor(), mock(JdbcTemplate.class));

    private static IoEdge edge(List<IoEdge> es, Direction dir, String table) {
        return es.stream()
                .filter(e -> e.direction() == dir && e.table().qualifiedName().equalsIgnoreCase(table))
                .findFirst().orElseThrow(() -> new AssertionError("no " + dir + " edge for " + table));
    }

    @Test
    void agent_declaration_and_sql_parse_agree_confirmed() {
        var a = assembler.assemble(1L, 1L, "SQL", "INSERT INTO dwd_order SELECT * FROM ods_order",
                List.of("ods_order"), List.of("dwd_order"), null, null);
        var w = edge(a.ioEdges(), Direction.WRITES, "dwd_order");
        assertThat(w.source()).isEqualTo(Source.AGENT);
        assertThat(w.confidence()).isEqualTo(Confidence.CONFIRMED);
        var r = edge(a.ioEdges(), Direction.READS, "ods_order");
        assertThat(r.source()).isEqualTo(Source.AGENT);
        assertThat(r.confidence()).isEqualTo(Confidence.CONFIRMED);
    }

    @Test
    void agent_declares_write_sql_does_not_conflict() {
        // Agent 声明写 dwd_order，但 SQL 只读不写 → CONFLICT
        var a = assembler.assemble(1L, 1L, "SQL", "SELECT * FROM ods_order",
                List.of(), List.of("dwd_order"), null, null);
        var w = edge(a.ioEdges(), Direction.WRITES, "dwd_order");
        assertThat(w.source()).isEqualTo(Source.AGENT);
        assertThat(w.confidence()).isEqualTo(Confidence.CONFLICT);
    }

    @Test
    void sql_parse_only_no_agent_declaration_confirmed() {
        var a = assembler.assemble(1L, 1L, "SQL", "INSERT INTO dwd_order SELECT * FROM ods_order",
                null, null, null, null);
        assertThat(edge(a.ioEdges(), Direction.WRITES, "dwd_order").source()).isEqualTo(Source.SQL_PARSED);
        assertThat(edge(a.ioEdges(), Direction.WRITES, "dwd_order").confidence()).isEqualTo(Confidence.CONFIRMED);
        assertThat(edge(a.ioEdges(), Direction.READS, "ods_order").source()).isEqualTo(Source.SQL_PARSED);
    }

    @Test
    void shell_unparseable_agent_declaration_unverified() {
        var a = assembler.assemble(1L, 1L, "SHELL", "spark-submit job.py",
                List.of("ods_log"), List.of("dwd_log"), null, null);
        assertThat(edge(a.ioEdges(), Direction.READS, "ods_log").confidence()).isEqualTo(Confidence.UNVERIFIED);
        assertThat(edge(a.ioEdges(), Direction.WRITES, "dwd_log").confidence()).isEqualTo(Confidence.UNVERIFIED);
        assertThat(edge(a.ioEdges(), Direction.WRITES, "dwd_log").source()).isEqualTo(Source.AGENT);
    }

    @Test
    void no_io_and_unparseable_records_nothing() {
        var a = assembler.assemble(1L, 1L, "SHELL", "echo hi", null, null, null, null);
        assertThat(a.ioEdges()).isEmpty();
    }
}
