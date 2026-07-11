package com.dataweave.master.application.lineage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dataweave.master.application.SqlTableExtractor;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 回归：数仓内 ETL（源=目标同库）未显式设 targetDatasource 时，写侧坐标必须回退到源数据源。
 *
 * <p>否则同一张表在「上游任务写」用退化占位坐标节点、在「下游任务读」用源库真实坐标节点 →
 * 两个 {@link DatasourceCoord#dsKey()} 不同 → 图内两个 :Table 节点 → 跨层 FLOWS_TO 断裂、多跳血缘溯源失效。
 * 该缺陷由 ods→dwd→dws→ads 真实数仓案例端到端验证时暴露。
 */
class LineageWriteCoordFallbackTest {

    private static final String WRITE_DWD = "insert into dwd_order_detail select * from ods_orders";
    private static final String READ_DWD = "insert into dws_user_order_summary select * from dwd_order_detail";
    private static final String KEY_SRC = "1|10.0.0.20|3306|shop"; // 源库 7 的真实 dsKey
    private static final String KEY_DEGRADED = "1|datasource:";     // 空坐标退化 dsKey

    private LineageEdgeAssembler assemblerBoundToDs7() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DatasourceCoord coord7 = new DatasourceCoord(1L, 1L, "10.0.0.20", 3306, "shop", "orders_mysql");
        when(jdbc.queryForObject(any(String.class), any(RowMapper.class), any())).thenReturn(coord7);
        return new LineageEdgeAssembler(new SqlTableExtractor(), jdbc);
    }

    private String coordKey(LineageEdgeAssembler.Assembly a, Direction dir) {
        return a.ioEdges().stream()
                .filter(e -> e.direction() == dir)
                .map(e -> e.table().datasource().dsKey())
                .findFirst().orElseThrow();
    }

    @Test
    void effectiveWriteDatasource_prefersTarget_elseFallsBackToSource() {
        assertThat(LineageEdgeAssembler.effectiveWriteDatasource(7L, null)).isEqualTo(7L);
        assertThat(LineageEdgeAssembler.effectiveWriteDatasource(7L, 9L)).isEqualTo(9L);
        assertThat(LineageEdgeAssembler.effectiveWriteDatasource(null, null)).isNull();
    }

    @Test
    void upstreamWriteAndDownstreamRead_unifyOnSourceCoord_whenTargetAbsent() {
        LineageEdgeAssembler assembler = assemblerBoundToDs7();
        // 上游写 dwd_order_detail：源库 7，无 target → 有效写库回退 7
        Long writeDs = LineageEdgeAssembler.effectiveWriteDatasource(7L, null);
        var upstream = assembler.assemble(1L, 1L, "SQL", WRITE_DWD, List.of(), List.of(), 7L, writeDs);
        // 下游读 dwd_order_detail：源库 7
        var downstream = assembler.assemble(1L, 1L, "SQL", READ_DWD, List.of(), List.of(),
                7L, LineageEdgeAssembler.effectiveWriteDatasource(7L, null));

        String writeKey = coordKey(upstream, Direction.WRITES);
        String readKey = coordKey(downstream, Direction.READS);
        // 修复效果：写节点键 == 读节点键 == 源库真实键 → 同一 :Table 节点 → ods→dwd→dws 链路连通
        assertThat(writeKey).isEqualTo(readKey).isEqualTo(KEY_SRC);
    }

    @Test
    void nullWriteDatasource_producesDegradedKey_documentingTheSplitBug() {
        LineageEdgeAssembler assembler = assemblerBoundToDs7();
        // 不回退（缺陷行为）：write ds = null → 写侧退化占位键，与下游读侧源库真实键不同 → 断链
        var up = assembler.assemble(1L, 1L, "SQL", WRITE_DWD, List.of(), List.of(), 7L, null);
        assertThat(coordKey(up, Direction.WRITES))
                .isEqualTo(KEY_DEGRADED)
                .isNotEqualTo(KEY_SRC);
    }
}
