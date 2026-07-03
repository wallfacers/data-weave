package com.dataweave.master.application.lineage.script;

import java.util.List;
import java.util.Set;

import com.dataweave.master.application.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.Source;

/**
 * 单个抽取器的名字级产物（041）。表/列以限定名字符串表达，坐标解析与图写入由编排层
 * （{@code ScriptLineageService}）统一完成——抽取器与存储/展示层解耦（FR-010）。
 *
 * @param reads        读表限定名集合
 * @param writes       写表限定名集合
 * @param columnEdges  列级派生边（名字级，复用 019 应用层契约；可空）
 * @param hints        未解析提示（动态表名/动态 SQL 等，FR-006；可空）
 * @param channel      通道来源：SCRIPT_SQL / SCRIPT_INFERRED / SCRIPT_MODEL
 * @param modelVersion 仅 SCRIPT_MODEL 通道：产出该结果的模型版本（FR-015 可回溯）
 */
public record ScriptExtraction(
        Set<String> reads,
        Set<String> writes,
        List<ColumnEdge> columnEdges,
        List<Hint> hints,
        Source channel,
        String modelVersion
) {

    /** 疑似读写点但静态无法确定目标（FR-006 宁缺毋滥：记录提示，不猜边）。 */
    public record Hint(HintKind kind, int line, String snippet) {}

    /** 提示形态（与 lineage_unresolved_hint.kind 取值一致）。 */
    public enum HintKind { DYNAMIC_TABLE, DYNAMIC_SQL, TIMEOUT, PARSE_FAIL }

    public static ScriptExtraction empty(Source channel) {
        return new ScriptExtraction(Set.of(), Set.of(), List.of(), List.of(), channel, null);
    }
}
