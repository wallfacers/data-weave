package com.dataweave.master.application.authoring;

import java.util.List;
import java.util.Map;

/**
 * 058 创作上下文包（US1）：某任务/工作副本草稿的意图接地视图（data-model.md）。
 * 纯读产物、可序列化（CLI/MCP/REST 共用），字段用简单类型以保持契约稳定。
 *
 * @param taskRef          任务标识（已 push 任务 id 或草稿逻辑名）
 * @param reads            读表及其上游生产者
 * @param writes           写表及其下游消费者
 * @param columnLineage    读写表相关的列级血缘
 * @param datasourceSchema 绑定数据源解析出的真实列（表名 → 列名列表）
 * @param depthUsed        实际遍历深度（调用方自决，回显）
 * @param truncated        因广度超阈值被截断的节点（FR-018，绝不静默丢失）
 * @param partial          事实源不可用致部分缺失（FR-005，绝不整体失败）
 */
public record AuthoringContext(
        String taskRef,
        List<TableFact> reads,
        List<TableFact> writes,
        List<ColumnEdgeFact> columnLineage,
        Map<String, List<String>> datasourceSchema,
        int depthUsed,
        List<TruncationNote> truncated,
        List<MissingNote> partial) {

    /** 表事实：读/写表 + 上游生产者或下游消费者邻居 + 接地态 + 来源。 */
    public record TableFact(
            String table,
            String datasource,
            String direction,      // READS | WRITES
            List<NodeRef> neighbors,
            String groundingState, // PRESENT | INFERRED | UNGROUNDED
            String source) {}

    /** 邻居节点引用（任务/表），带跳距。 */
    public record NodeRef(String id, String name, String kind, int hop) {}  // kind: TASK | TABLE

    /** 列级血缘边：dstTable.dstColumn ← srcTable.srcColumn。 */
    public record ColumnEdgeFact(String srcTable, String srcColumn, String dstTable, String dstColumn) {}

    /** 截断留痕：某节点因广度超阈值未继续展开。 */
    public record TruncationNote(String at, String reason) {}

    /** 缺失留痕：某事实源不可用致部分缺失。 */
    public record MissingNote(String source, String reason) {}
}
