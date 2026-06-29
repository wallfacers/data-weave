package com.dataweave.master.application.lineage;

import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 降级阶梯第 2 级：Calcite 列溯源不可用（缺源表元数据等）时，对 INSERT 的直引列做
 * <b>AST 启发式</b>按名匹配，产出 {@link Confidence#UNVERIFIED} 边 —— 比完全留空更有价值，
 * 但明确标低可信度。
 *
 * <p>仅处理「显式目标列 + 简单直引（SqlIdentifier）」，表达式/聚合/{@code *} 不猜（不可靠）。
 * 永不抛异常。
 */
final class ColumnLineageDegrade {

    private ColumnLineageDegrade() {
    }

    static List<ColumnEdge> heuristic(SqlInsert insert) {
        try {
            return doHeuristic(insert);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<ColumnEdge> doHeuristic(SqlInsert insert) {
        if (!(insert.getTargetTable() instanceof SqlIdentifier targetId)) {
            return List.of();
        }
        String target = NameNormalizer.table(targetId);
        List<String> targetCols = explicitTargetCols(insert);
        if (targetCols.isEmpty()) {
            return List.of();
        }
        SqlSelect select = asSelect(insert.getSource());
        if (select == null || select.getSelectList() == null) {
            return List.of();
        }

        // FROM 别名 → 物理表名映射 + 物理表清单
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        collectFrom(select.getFrom(), aliasToTable, tables);

        SqlNodeList items = select.getSelectList();
        List<ColumnEdge> out = new ArrayList<>();
        int n = Math.min(items.size(), targetCols.size());
        for (int i = 0; i < n; i++) {
            SqlNode item = stripAs(items.get(i));
            if (!(item instanceof SqlIdentifier id)) {
                continue; // 表达式/聚合/* 不猜
            }
            String col = id.names.get(id.names.size() - 1);
            if ("*".equals(col)) {
                continue;
            }
            String table = resolveTable(id, aliasToTable, tables);
            if (table == null) {
                continue; // 无法消歧（多表且无限定）
            }
            out.add(new ColumnEdge(
                    TableRef.of(table), NameNormalizer.column(col),
                    TableRef.of(target), NameNormalizer.column(targetCols.get(i)),
                    Transform.DIRECT, Confidence.UNVERIFIED));
        }
        return out;
    }

    private static String resolveTable(SqlIdentifier id, Map<String, String> aliasToTable, List<String> tables) {
        if (id.names.size() >= 2) {
            String qualifier = id.names.get(id.names.size() - 2);
            if (aliasToTable.containsKey(qualifier.toLowerCase())) {
                return aliasToTable.get(qualifier.toLowerCase());
            }
            // 限定名前缀直接当表名（保留多段 schema 前缀）
            List<String> prefix = id.names.subList(0, id.names.size() - 1);
            return NameNormalizer.table(prefix);
        }
        // 非限定列：仅单表时可消歧
        return tables.size() == 1 ? tables.get(0) : null;
    }

    private static List<String> explicitTargetCols(SqlInsert insert) {
        SqlNodeList cols = insert.getTargetColumnList();
        List<String> out = new ArrayList<>();
        if (cols != null) {
            for (SqlNode c : cols) {
                if (c instanceof SqlIdentifier id) {
                    out.add(NameNormalizer.column(id.names.get(id.names.size() - 1)));
                }
            }
        }
        return out;
    }

    private static SqlSelect asSelect(SqlNode node) {
        if (node instanceof SqlOrderBy ob) {
            node = ob.query;
        }
        return node instanceof SqlSelect sel ? sel : null;
    }

    /** 递归收集 FROM 的物理表与别名（仅简单表/AS 别名；子查询不入）。 */
    private static void collectFrom(SqlNode from, Map<String, String> aliasToTable, List<String> tables) {
        if (from == null) {
            return;
        }
        switch (from) {
            case SqlIdentifier id -> {
                String t = NameNormalizer.table(id);
                tables.add(t);
                aliasToTable.put(id.names.get(id.names.size() - 1).toLowerCase(), t);
            }
            case SqlJoin join -> {
                collectFrom(join.getLeft(), aliasToTable, tables);
                collectFrom(join.getRight(), aliasToTable, tables);
            }
            case SqlBasicCall call -> {
                if (call.getKind() == SqlKind.AS && call.operandCount() >= 2
                        && call.operand(0) instanceof SqlIdentifier tableId
                        && call.operand(1) instanceof SqlIdentifier aliasId) {
                    String t = NameNormalizer.table(tableId);
                    tables.add(t);
                    aliasToTable.put(aliasId.getSimple().toLowerCase(), t);
                }
                // 子查询 AS 别名：无元数据不猜
            }
            default -> {
                // 其它（子查询包裹等）不处理
            }
        }
    }

    private static SqlNode stripAs(SqlNode node) {
        if (node instanceof SqlBasicCall call && call.getKind() == SqlKind.AS && call.operandCount() > 0) {
            return call.operand(0);
        }
        return node;
    }
}
