package com.dataweave.master.application.lineage.agent;

import java.util.List;

/**
 * 053 云 AI Agent 抽取的协议归一产物（Anthropic/OpenAI 两协议解析后统一为此结构，FR-002）。
 * 由 {@code AgentLineageExtractor} 经防幻觉校验（契约 C2）后转为 {@code ScriptExtraction}（channel=SCRIPT_AGENT）。
 *
 * @param reads        读表名（字面须能在脚本中定位，C2）
 * @param writes       写表名
 * @param columnEdges  字段级派生（名字级，未绑数据源坐标；US3 schema 接地后约束落在真实列集合内）
 * @param confidence   模型自评置信度 [0,1]
 * @param modelVersion 模型名快照（可回溯）
 */
public record AgentExtraction(
        List<String> reads,
        List<String> writes,
        List<ColumnEdge> columnEdges,
        double confidence,
        String modelVersion
) {
    /** AI 输出的字段级派生（名字级）。 */
    public record ColumnEdge(String srcTable, String srcColumn, String dstTable, String dstColumn) {}

    public static AgentExtraction empty(String modelVersion) {
        return new AgentExtraction(List.of(), List.of(), List.of(), 0.0, modelVersion);
    }
}
