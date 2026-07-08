package com.dataweave.master.application.authoring;

import java.util.List;

import com.dataweave.master.application.lineage.script.ScriptExtraction;
import com.dataweave.master.application.lineage.script.ScriptLineageService;
import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import com.dataweave.master.domain.lineage.Transform;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 058 T005（Foundational）：{@link DraftLineage} 归一映射单测。
 * 验证草稿抽取结果（既有 extractor 产物）→ reads/writes/列边/hints 的确定性映射；
 * 不经服务即测映射，佐证「只归一、不 fork 第二套抽取」（research D3）。
 */
class DraftLineageTest {

    private static TableRef table(String qn) {
        return new TableRef(new DatasourceCoord(1L, 1L, null, null, null, "ds"), qn, null);
    }

    @Test
    void mapsIoEdgesToReadsAndWrites() {
        var result = new ScriptLineageService.Result(
                List.of(
                        new IoEdge(table("ods.user"), Direction.READS, Source.SCRIPT_SQL, Confidence.UNVERIFIED),
                        new IoEdge(table("dw.user_daily"), Direction.WRITES, Source.SCRIPT_SQL, Confidence.UNVERIFIED)),
                List.of(),
                List.of());

        DraftLineage d = DraftLineage.fromResult("dw_user_daily", "SQL", 1L, result);

        assertThat(d.reads()).containsExactly("ods.user");
        assertThat(d.writes()).containsExactly("dw.user_daily");
        assertThat(d.columnEdges()).isEmpty();
        assertThat(d.hints()).isEmpty();
        assertThat(d.taskRef()).isEqualTo("dw_user_daily");
    }

    @Test
    void mapsColumnEdgesAndHints() {
        var col = new ColumnEdge(table("ods.user"), "id", table("dw.user_daily"), "id",
                Transform.DIRECT, Confidence.UNVERIFIED);
        var result = new ScriptLineageService.Result(
                List.of(),
                List.of(col),
                List.of(new ScriptExtraction.Hint(ScriptExtraction.HintKind.PARSE_FAIL, 0, "dynamic table skipped")));

        DraftLineage d = DraftLineage.fromResult("t", "SQL", 1L, result);

        assertThat(d.columnEdges()).hasSize(1);
        assertThat(d.columnEdges().get(0).srcTable()).isEqualTo("ods.user");
        assertThat(d.columnEdges().get(0).srcColumn()).isEqualTo("id");
        assertThat(d.columnEdges().get(0).dstTable()).isEqualTo("dw.user_daily");
        assertThat(d.hints()).containsExactly("dynamic table skipped");
    }

    @Test
    void nullResultYieldsEmptyDraft() {
        DraftLineage d = DraftLineage.fromResult("t", "PYTHON", null, null);
        assertThat(d.reads()).isEmpty();
        assertThat(d.writes()).isEmpty();
        assertThat(d.columnEdges()).isEmpty();
    }
}
