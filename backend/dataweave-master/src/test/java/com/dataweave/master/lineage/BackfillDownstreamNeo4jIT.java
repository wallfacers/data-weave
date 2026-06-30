package com.dataweave.master.lineage;

import java.util.List;

import com.dataweave.master.application.LineageQueryService;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import com.dataweave.master.infrastructure.lineage.Neo4jLineageGraphReader;
import com.dataweave.master.infrastructure.lineage.Neo4jLineageStore;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A1 任务级下游迁移等价性验证：neo4j {@link LineageQueryService#downstreamTaskLevels} 与
 * 旧 PG {@code LineageGraphService.downstreamLevels}（读 {@code task_table_io} 的任务级 BFS）语义一致。
 * 建 9001→9002→9003 任务级链，断言下游闭包 + BFS 层级 + 空叶子。真 neo4j 容器。
 */
class BackfillDownstreamNeo4jIT extends Neo4jTestSupport {

    private static final long T = 1L;
    private static final long P = 1L;
    private static final DatasourceCoord COORD = new DatasourceCoord(T, P, "10.0.0.1", 5432, "db", null);

    @Test
    void downstreamTaskLevels_matchesPgBfsSemantics() {
        Driver driver = newDriver();
        cleanDb(driver);
        Neo4jLineageStore store = newStore();

        TableRef ods = new TableRef(COORD, "ods_order", "ODS");
        TableRef dwd = new TableRef(COORD, "dwd_order", "DWD");
        TableRef dws = new TableRef(COORD, "dws_order", "DWS");
        TableRef ads = new TableRef(COORD, "ads_order", "ADS");

        // 9001: READS ods → WRITES dwd；9002: READS dwd → WRITES dws；9003: READS dws → WRITES ads
        store.recordTaskIo(T, P, 9001L, 1, "etl_ods_to_dwd",
                List.of(io(ods, Direction.READS), io(dwd, Direction.WRITES)), List.of());
        store.recordTaskIo(T, P, 9002L, 1, "etl_dwd_to_dws",
                List.of(io(dwd, Direction.READS), io(dws, Direction.WRITES)), List.of());
        store.recordTaskIo(T, P, 9003L, 1, "etl_dws_to_ads",
                List.of(io(dws, Direction.READS), io(ads, Direction.WRITES)), List.of());

        LineageQueryService query = new LineageQueryService(new Neo4jLineageGraphReader(driver));

        // 9001 下游：9002(level1)、9003(level2)
        assertThat(query.downstreamTaskLevels(T, P, 9001L))
                .containsEntry(9002L, 1).containsEntry(9003L, 2).hasSize(2);
        // 9002 下游：9003(level1)
        assertThat(query.downstreamTaskLevels(T, P, 9002L))
                .containsEntry(9003L, 1).hasSize(1);
        // 9003 下游：空（叶子）
        assertThat(query.downstreamTaskLevels(T, P, 9003L)).isEmpty();
    }

    private static IoEdge io(TableRef t, Direction dir) {
        return new IoEdge(t, dir, Source.SQL_PARSED, Confidence.CONFIRMED);
    }
}
