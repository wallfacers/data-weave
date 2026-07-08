package com.dataweave.master.application.authoring;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.dataweave.master.application.lineage.script.ScriptLineageService;
import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.application.lineage.script.ScriptSource;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;

/**
 * 058 草稿血缘归一产物（Foundational，data-model.md）。
 *
 * <p><b>硬不变量（research D3）</b>：草稿的读写表/列抽取只经既有 {@link ScriptLineageService}
 * （脚本）+ Calcite（SQL，由该服务内部通道承担），本类仅做<b>归一映射</b>——
 * 绝不实现第二套抽取逻辑，以保证与 push 时抽取零语义漂移。
 *
 * @param taskRef      草稿逻辑名
 * @param type         任务类型
 * @param datasourceId 绑定数据源
 * @param reads        抽取出的读表（限定名）
 * @param writes       抽取出的写表（限定名）
 * @param columnEdges  抽取出的列边
 * @param hints        解析降级/未定位留痕（防幻觉）
 */
public record DraftLineage(
        String taskRef,
        String type,
        Long datasourceId,
        Set<String> reads,
        Set<String> writes,
        List<AuthoringContext.ColumnEdgeFact> columnEdges,
        List<String> hints) {

    /** 经既有 extractor 抽取一个草稿并归一（复用，不 fork）。类型不受支持时返回空归一。 */
    public static DraftLineage from(ScriptSource source, ScriptLineageService service) {
        String ref = source.taskDefId() != null ? String.valueOf(source.taskDefId()) : source.taskType();
        if (!service.handles(source.taskType())) {
            return new DraftLineage(ref, source.taskType(), source.datasourceId(),
                    Set.of(), Set.of(), List.of(), List.of());
        }
        return fromResult(ref, source.taskType(), source.datasourceId(), service.extract(source));
    }

    /** 从既有抽取结果归一（单测入口——不经服务即可验证映射正确）。 */
    public static DraftLineage fromResult(String taskRef, String type, Long datasourceId,
                                          ScriptLineageService.Result result) {
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        List<AuthoringContext.ColumnEdgeFact> cols = new ArrayList<>();
        List<String> hints = new ArrayList<>();

        if (result != null) {
            for (IoEdge e : result.ioEdges()) {
                String qn = e.table() != null ? e.table().qualifiedName() : null;
                if (qn == null || qn.isBlank()) continue;
                if (e.direction() == Direction.READS) reads.add(qn);
                else if (e.direction() == Direction.WRITES) writes.add(qn);
            }
            for (ColumnEdge c : result.columnEdges()) {
                cols.add(new AuthoringContext.ColumnEdgeFact(
                        c.srcTable() != null ? c.srcTable().qualifiedName() : null, c.srcCol(),
                        c.dstTable() != null ? c.dstTable().qualifiedName() : null, c.dstCol()));
            }
            result.hints().forEach(h -> hints.add(h.snippet()));
        }
        return new DraftLineage(taskRef, type, datasourceId, reads, writes, cols, hints);
    }
}
