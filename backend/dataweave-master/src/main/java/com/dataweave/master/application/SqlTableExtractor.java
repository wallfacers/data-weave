package com.dataweave.master.application;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.parser.SqlParser;
import com.dataweave.master.application.lineage.NameNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用 Apache Calcite 解析 SQL，提取读表（FROM/JOIN）与写表（INSERT INTO / MERGE 目标）。
 *
 * <p>纯增强用途，给 {@code task_table_io} 提供 SQL_PARSED 来源并与 AGENT 声明交叉校验。
 * 解析失败（方言不支持/DDL/动态 SQL）<b>不抛异常</b>，返回 {@code parsed=false} 空结果——
 * 调用方据此降级为 UNVERIFIED，绝不阻断建任务主链路。
 */
@Component
public class SqlTableExtractor {

    private static final Logger log = LoggerFactory.getLogger(SqlTableExtractor.class);

    /** 解析结果。parsed=false 表示无法可靠解析，reads/writes 应视为「未知」而非「空」。 */
    public record Result(boolean parsed, Set<String> reads, Set<String> writes) {
        static Result unparsed() {
            return new Result(false, Set.of(), Set.of());
        }
    }

    private SqlParser.Config config() {
        return SqlParser.config()
                .withCaseSensitive(false)
                .withUnquotedCasing(Casing.UNCHANGED)
                .withQuotedCasing(Casing.UNCHANGED);
    }

    /** 解析 SQL（可含多语句，分号分隔），合并所有语句的读/写表。 */
    public Result extract(String sql) {
        if (sql == null || sql.isBlank()) return Result.unparsed();
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        try {
            SqlParser parser = SqlParser.create(sql, config());
            SqlNodeList stmts;
            try {
                stmts = parser.parseStmtList();
            } catch (Exception multi) {
                // 退回单语句解析
                SqlNode one = SqlParser.create(sql, config()).parseStmt();
                stmts = new SqlNodeList(one == null ? java.util.List.of() : java.util.List.of(one),
                        org.apache.calcite.sql.parser.SqlParserPos.ZERO);
            }
            for (SqlNode stmt : stmts) {
                visit(stmt, reads, writes);
            }
            return new Result(true, reads, writes);
        } catch (Exception e) {
            log.debug("SQL 解析失败，降级为 UNVERIFIED：{}", e.getMessage());
            return Result.unparsed();
        }
    }

    private void visit(SqlNode node, Set<String> reads, Set<String> writes) {
        if (node == null) return;
        if (node instanceof SqlInsert insert) {
            String target = tableName(insert.getTargetTable());
            if (target != null) writes.add(target);
            collectSources(insert.getSource(), reads);
            return;
        }
        if (node.getKind() == SqlKind.MERGE && node instanceof SqlCall merge) {
            // MERGE INTO target USING source：operand 0=target，其余递归找源
            if (merge.operandCount() > 0) {
                String target = tableName(merge.operand(0));
                if (target != null) writes.add(target);
            }
            for (int i = 1; i < merge.operandCount(); i++) {
                collectSources(merge.operand(i), reads);
            }
            return;
        }
        // 纯查询（SELECT/WITH/ORDER BY 包裹）：全部计入读
        collectSources(node, reads);
    }

    /** 递归收集 FROM 子树中的源表（含 JOIN/子查询/AS 别名/WITH/UNION）。 */
    private void collectSources(SqlNode from, Set<String> reads) {
        if (from == null) return;
        switch (from) {
            case SqlIdentifier id -> {
                String n = tableName(id);
                if (n != null) reads.add(n);
            }
            case SqlJoin join -> {
                collectSources(join.getLeft(), reads);
                collectSources(join.getRight(), reads);
            }
            case SqlSelect select -> collectSources(select.getFrom(), reads);
            case SqlOrderBy orderBy -> collectSources(orderBy.query, reads);
            case SqlWith with -> {
                // WITH 的 CTE 名要从源表中排除（它们是临时别名，非物理表）
                Set<String> cteNames = new LinkedHashSet<>();
                for (SqlNode item : with.withList) {
                    if (item instanceof SqlWithItem wi && wi.name != null) {
                        cteNames.add(wi.name.toString().toLowerCase());
                        collectSources(wi.query, reads);
                    }
                }
                collectSources(with.body, reads);
                reads.removeIf(r -> cteNames.contains(r.toLowerCase()));
            }
            case SqlBasicCall call -> {
                if (call.getKind() == SqlKind.AS && call.operandCount() > 0) {
                    collectSources(call.operand(0), reads); // 别名前的真实表/子查询
                } else {
                    // UNION/INTERSECT/EXCEPT 等集合操作，递归所有操作数
                    for (SqlNode op : call.getOperandList()) {
                        collectSources(op, reads);
                    }
                }
            }
            default -> {
                if (from instanceof SqlCall c) {
                    for (SqlNode op : c.getOperandList()) {
                        collectSources(op, reads);
                    }
                }
            }
        }
    }

    /** SqlIdentifier → 点分表名（保留 schema 前缀，去引号）。规范化规则与列级共用，见 {@link NameNormalizer}。 */
    private String tableName(SqlNode node) {
        if (node instanceof SqlIdentifier id) {
            return NameNormalizer.table(id);
        }
        return null;
    }
}
