package com.dataweave.master.lineage;

import java.util.List;

import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import com.dataweave.master.domain.lineage.Transform;
import com.dataweave.master.infrastructure.lineage.Neo4jLineageGraphReader;
import com.dataweave.master.infrastructure.lineage.Neo4jLineageStore;
import com.dataweave.master.lineage.GraphNodeView.Granularity;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整合期接缝端到端验证：018 {@link Neo4jLineageStore} 写入 → {@link Neo4jLineageGraphReader}（整合补的
 * T042 实现）→ 020 {@link LineageQueryService} 读回。证明三特性合一后写读图模型对齐（节点 {@code id} 镜像、
 * FLOWS_TO/DERIVES_FROM/HAS_COLUMN 关系一致），功能闭环（非各自孤立通过）。真 neo4j 容器。
 */
class LineageSeamE2EIT extends Neo4jTestSupport {

    private static final long T = 1L;
    private static final long P = 1L;

    // 与 Neo4jLineageStore 的 dsKey/tableKey/columnKey 合成规则一致，用于按 id 查询
    private static final DatasourceCoord COORD = new DatasourceCoord(T, P, "10.0.0.1", 5432, "db", null);
    private static final String DS_KEY = COORD.dsKey();                       // 1|10.0.0.1|5432|db
    private static final String SRC_TK = DS_KEY + "|ods_orders";
    private static final String DST_TK = DS_KEY + "|dwd_orders";
    private static final String SRC_CK = SRC_TK + "|id";

    @Test
    void writeViaStoreThenReadViaQueryService_seamCloses() {
        Driver driver = newDriver();
        cleanDb(driver);
        Neo4jLineageStore store = newStore();

        TableRef src = new TableRef(COORD, "ods_orders", "ODS");
        TableRef dst = new TableRef(COORD, "dwd_orders", "DWD");
        List<IoEdge> io = List.of(
                new IoEdge(src, Direction.READS, Source.SQL_PARSED, Confidence.CONFIRMED),
                new IoEdge(dst, Direction.WRITES, Source.SQL_PARSED, Confidence.CONFIRMED));
        List<ColumnEdge> cols = List.of(
                new ColumnEdge(src, "id", dst, "id", Transform.DIRECT, Confidence.CONFIRMED));

        store.recordTaskIo(T, P, 100L, 1, "etl_ods_to_dwd", io, cols, null);

        LineageQueryService query = new LineageQueryService(new Neo4jLineageGraphReader(driver));

        // 1. 数据源去重节点可被读回
        assertThat(query.datasources(T, P, 0, 100))
                .extracting(GraphNodeView::id)
                .contains(DS_KEY);

        // 2. 表级下游（FLOWS_TO 变长路径）：ods_orders → dwd_orders
        assertThat(query.downstream(T, P, SRC_TK, 5, Granularity.TABLE).nodes())
                .extracting(GraphNodeView::name)
                .contains("dwd_orders");

        // 3. 列级下游（DERIVES_FROM）：ods_orders.id → dwd_orders.id
        assertThat(query.columnDownstream(T, P, SRC_CK, 5).nodes())
                .isNotEmpty();

        // 4. 目标表的列可被读回（HAS_COLUMN）
        assertThat(query.columns(T, P, DST_TK, 0, 100))
                .extracting(GraphNodeView::name)
                .contains("id");
    }
}
