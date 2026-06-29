package com.dataweave.master.application.lineage;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.metadata.RelColumnOrigin;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 列级血缘核心引擎：用 Apache Calcite 把一条 SQL 解析为关系代数（RelNode），
 * 再用 {@link RelMetadataQuery#getColumnOrigins(RelNode, int)} 取每个输出列的物理源列。
 *
 * <p>{@code getColumnOrigins} 原生穿透 JOIN / WITH CTE / UNION / 子查询 / 投影，省去手写作用域解析。
 * 代价是需要已校验的 RelNode → 需要 SqlValidator → 需要 catalog（{@link ColumnLineageCatalog}）。
 *
 * <p><b>本类不吞所有异常</b>：调用方 {@code SqlColumnLineageExtractor} 负责 try-catch 兜底（契约 C1）。
 * 单条语句无法解析时本类内部降级（跳过/标 {@code degraded}），不影响其它语句。
 */
public final class CalciteColumnLineage {

    private static final Logger log = LoggerFactory.getLogger(CalciteColumnLineage.class);

    private final RelDataTypeFactory typeFactory = new JavaTypeFactoryImpl();

    /**
     * 分析 SQL，产出列级派生边。
     *
     * @param sql             任务脚本（可含多条分号分隔语句）
     * @param catalog         列元数据来源
     * @param candidateTables 已规范化的候选表名（源+目标），用于构建 Calcite schema
     */
    public ColumnLineageResult analyze(String sql, ColumnLineageCatalog catalog, Set<String> candidateTables) {
        ColumnLineageCatalog cat = catalog == null ? ColumnLineageCatalog.EMPTY : catalog;

        // 1. 构建 schema（仅注册 catalog 能解析的表）
        CalciteSchema root = CalciteSchema.createRootSchema(false, false);
        for (String t : candidateTables) {
            cat.lookupTable(t).ifPresent(ts -> registerTable(root, t, ts));
        }
        Prepare.CatalogReader catalogReader = new CalciteCatalogReader(
                root, List.of(), typeFactory, connectionConfig());

        // 2. 解析语句
        SqlNodeList stmts = parse(sql);
        if (stmts == null) {
            return ColumnLineageResult.unparsed();
        }

        List<ColumnEdge> edges = new ArrayList<>();
        boolean degraded = false;
        boolean anyParsed = false;
        for (SqlNode stmt : stmts) {
            if (!(stmt instanceof SqlInsert insert)) {
                // 纯查询无写入目标、DDL/MERGE 暂不产列边——交由表级与 US3 处理
                continue;
            }
            try {
                StmtOutcome outcome = processInsert(insert, cat, catalogReader);
                if (!outcome.edges().isEmpty()) {
                    anyParsed = true;
                    edges.addAll(outcome.edges());
                } else {
                    // 主路径没产出（目标列未知/源表缺元数据）→ AST 启发式降级补 UNVERIFIED
                    List<ColumnEdge> h = ColumnLineageDegrade.heuristic(insert);
                    if (!h.isEmpty()) {
                        anyParsed = true;
                        edges.addAll(h);
                    }
                }
                degraded |= outcome.degraded();
            } catch (Exception e) {
                // 单语句校验/转换失败：退 AST 启发式降级，仍不上抛
                log.debug("列级解析单语句失败，降级：{}", e.toString());
                degraded = true;
                List<ColumnEdge> h = ColumnLineageDegrade.heuristic(insert);
                if (!h.isEmpty()) {
                    anyParsed = true;
                    edges.addAll(h);
                }
            }
        }
        if (!anyParsed && edges.isEmpty()) {
            return new ColumnLineageResult(false, List.of(), degraded);
        }
        return new ColumnLineageResult(true, dedup(edges), degraded);
    }

    /** 处理一条 INSERT ... SELECT/UNION，映射目标列 ← 源列。 */
    private StmtOutcome processInsert(SqlInsert insert, ColumnLineageCatalog cat,
                                      Prepare.CatalogReader catalogReader) {
        String target = NameNormalizer.table((SqlIdentifier) insert.getTargetTable());
        List<String> targetCols = targetColumns(insert, target, cat);
        if (targetCols == null || targetCols.isEmpty()) {
            // 目标列名未知（无显式列清单且 catalog 无该表）→ 列级降级
            return new StmtOutcome(List.of(), true);
        }

        SqlNode source = insert.getSource();

        // 校验 + 转 RelNode
        SqlValidator validator = SqlValidatorUtil.newValidator(
                SqlOperatorTables.chain(SqlStdOperatorTable.instance(), (org.apache.calcite.sql.SqlOperatorTable) catalogReader),
                catalogReader, typeFactory,
                SqlValidator.Config.DEFAULT.withIdentifierExpansion(true));
        SqlNode validated = validator.validate(source);

        VolcanoPlanner planner = new VolcanoPlanner();
        planner.addRelTraitDef(org.apache.calcite.plan.ConventionTraitDef.INSTANCE);
        RelOptCluster cluster = RelOptCluster.create(planner, new RexBuilder(typeFactory));
        RelOptTable.ViewExpander viewExpander =
                (rowType, queryString, schemaPath, viewPath) -> null;
        SqlToRelConverter converter = new SqlToRelConverter(
                viewExpander, validator, catalogReader, cluster,
                StandardConvertletTable.INSTANCE,
                SqlToRelConverter.config().withTrimUnusedFields(false));
        RelRoot relRoot = converter.convertQuery(validated, false, true);
        RelNode rel = relRoot.project();
        RelMetadataQuery mq = cluster.getMetadataQuery();

        SqlNodeList selectList = selectListOf(validated);
        int fieldCount = rel.getRowType().getFieldCount();
        int n = Math.min(targetCols.size(), fieldCount);

        List<ColumnEdge> edges = new ArrayList<>();
        boolean degraded = targetCols.size() != fieldCount;
        for (int i = 0; i < n; i++) {
            String dstCol = targetCols.get(i);
            Set<RelColumnOrigin> origins = mq.getColumnOrigins(rel, i);
            if (origins == null || origins.isEmpty()) {
                // 溯源为空（窗口函数/UDF/常量）：US3 再补 AST 启发式，这里标降级
                degraded = true;
                continue;
            }
            Transform transform = classify(origins, selectList, i);
            for (RelColumnOrigin origin : origins) {
                String srcTable = NameNormalizer.table(origin.getOriginTable().getQualifiedName());
                String srcCol = origin.getOriginTable().getRowType()
                        .getFieldList().get(origin.getOriginColumnOrdinal()).getName();
                edges.add(new ColumnEdge(
                        TableRef.of(srcTable), NameNormalizer.column(srcCol),
                        TableRef.of(target), NameNormalizer.column(dstCol),
                        transform, Confidence.CONFIRMED));
            }
        }
        return new StmtOutcome(edges, degraded);
    }

    /** 目标列：显式列清单优先，否则取 catalog 中目标表的有序列。 */
    private List<String> targetColumns(SqlInsert insert, String target, ColumnLineageCatalog cat) {
        SqlNodeList cols = insert.getTargetColumnList();
        if (cols != null && !cols.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (SqlNode c : cols) {
                if (c instanceof SqlIdentifier id) {
                    out.add(NameNormalizer.column(id.names.get(id.names.size() - 1)));
                }
            }
            return out;
        }
        return cat.lookupTable(target)
                .map(ts -> ts.columns().stream().map(ColumnMeta::name).toList())
                .orElse(null);
    }

    /** transform 分类：聚合 > 表达式 > 直传。 */
    private Transform classify(Set<RelColumnOrigin> origins, SqlNodeList selectList, int i) {
        if (isAggregate(selectItem(selectList, i))) {
            return Transform.AGGREGATE;
        }
        boolean anyDerived = origins.stream().anyMatch(RelColumnOrigin::isDerived);
        return anyDerived ? Transform.EXPRESSION : Transform.DIRECT;
    }

    private SqlNode selectItem(SqlNodeList selectList, int i) {
        if (selectList == null || i >= selectList.size()) return null;
        return stripAs(selectList.get(i));
    }

    private boolean isAggregate(SqlNode item) {
        if (item instanceof SqlCall call) {
            return call.getOperator() instanceof SqlAggFunction;
        }
        return false;
    }

    private SqlNode stripAs(SqlNode node) {
        if (node instanceof SqlBasicCall call && call.getKind() == SqlKind.AS && call.operandCount() > 0) {
            return call.operand(0);
        }
        return node;
    }

    private SqlNodeList selectListOf(SqlNode validated) {
        SqlNode q = validated;
        if (q instanceof SqlOrderBy ob) q = ob.query;
        if (q instanceof SqlSelect sel) return sel.getSelectList();
        return null;
    }

    /** 同一 (src,dst) 列对去重，保留首次出现。 */
    private List<ColumnEdge> dedup(List<ColumnEdge> edges) {
        Map<String, ColumnEdge> seen = new LinkedHashMap<>();
        for (ColumnEdge e : edges) {
            if (isBlank(e.srcTable().qualifiedName()) || isBlank(e.srcCol())
                    || isBlank(e.dstTable().qualifiedName()) || isBlank(e.dstCol())) {
                continue; // 空白名丢弃，沿用表级
            }
            String key = e.srcTable().qualifiedName() + "" + e.srcCol()
                    + "" + e.dstTable().qualifiedName() + "" + e.dstCol();
            seen.putIfAbsent(key, e);
        }
        return new ArrayList<>(seen.values());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ---- Calcite 基础设施 ----

    private SqlNodeList parse(String sql) {
        try {
            SqlParser.Config cfg = SqlParser.config()
                    .withCaseSensitive(false)
                    .withUnquotedCasing(Casing.UNCHANGED)
                    .withQuotedCasing(Casing.UNCHANGED);
            try {
                return SqlParser.create(sql, cfg).parseStmtList();
            } catch (Exception multi) {
                SqlNode one = SqlParser.create(sql, cfg).parseStmt();
                return new SqlNodeList(one == null ? List.of() : List.of(one), SqlParserPos.ZERO);
            }
        } catch (Exception e) {
            log.debug("列级解析 parse 失败：{}", e.getMessage());
            return null;
        }
    }

    private CalciteConnectionConfig connectionConfig() {
        Properties props = new Properties();
        props.put(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), "false");
        return new CalciteConnectionConfigImpl(props);
    }

    private void registerTable(CalciteSchema root, String qualifiedName, TableSchema ts) {
        String[] parts = qualifiedName.split("\\.");
        org.apache.calcite.schema.SchemaPlus sp = root.plus();
        for (int k = 0; k < parts.length - 1; k++) {
            org.apache.calcite.schema.SchemaPlus child = sp.getSubSchema(parts[k]);
            if (child == null) {
                child = sp.add(parts[k], new AbstractSchema());
            }
            sp = child;
        }
        sp.add(parts[parts.length - 1], new CatalogTable(ts, typeFactory));
    }

    /** catalog 元数据适配为 Calcite Table（仅提供 row type 供 validator 解析列引用）。 */
    private static final class CatalogTable extends AbstractTable {
        private final TableSchema ts;
        private final RelDataTypeFactory typeFactory;

        CatalogTable(TableSchema ts, RelDataTypeFactory typeFactory) {
            this.ts = ts;
            this.typeFactory = typeFactory;
        }

        @Override
        public RelDataType getRowType(RelDataTypeFactory tf) {
            RelDataTypeFactory.Builder b = tf.builder();
            Set<String> used = new LinkedHashSet<>();
            for (ColumnMeta c : ts.columns()) {
                String name = c.name();
                // Calcite row type 不允许重名列；极端情况下去重保护
                if (!used.add(name.toLowerCase())) continue;
                RelDataType type = tf.createSqlType(sqlType(c.dataType()));
                b.add(name, tf.createTypeWithNullability(type, true));
            }
            return b.build();
        }

        private static SqlTypeName sqlType(String dataType) {
            if (dataType == null) return SqlTypeName.VARCHAR;
            String d = dataType.trim().toUpperCase();
            try {
                // 取基础类型名（去掉 (precision) 后缀）
                int paren = d.indexOf('(');
                if (paren > 0) d = d.substring(0, paren).trim();
                return switch (d) {
                    case "INT", "INTEGER", "INT4" -> SqlTypeName.INTEGER;
                    case "BIGINT", "INT8", "LONG" -> SqlTypeName.BIGINT;
                    case "SMALLINT" -> SqlTypeName.SMALLINT;
                    case "TINYINT" -> SqlTypeName.TINYINT;
                    case "DOUBLE", "FLOAT8" -> SqlTypeName.DOUBLE;
                    case "FLOAT", "REAL" -> SqlTypeName.FLOAT;
                    case "DECIMAL", "NUMERIC", "NUMBER" -> SqlTypeName.DECIMAL;
                    case "BOOLEAN", "BOOL" -> SqlTypeName.BOOLEAN;
                    case "DATE" -> SqlTypeName.DATE;
                    case "TIME" -> SqlTypeName.TIME;
                    case "TIMESTAMP", "DATETIME" -> SqlTypeName.TIMESTAMP;
                    case "CHAR" -> SqlTypeName.CHAR;
                    default -> SqlTypeName.VARCHAR;
                };
            } catch (Exception e) {
                return SqlTypeName.VARCHAR;
            }
        }
    }

    private record StmtOutcome(List<ColumnEdge> edges, boolean degraded) {
        StmtOutcome {
            edges = edges == null ? List.of() : edges;
        }
    }
}
