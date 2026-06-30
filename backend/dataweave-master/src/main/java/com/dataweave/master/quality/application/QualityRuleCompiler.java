package com.dataweave.master.quality.application;

import com.dataweave.master.quality.domain.AssertionType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 8 类断言 → 度量 SQL 编译（research D6）。
 *
 * <p>每类断言根据 {@link QualityExpectation} + datasetRef + samplingConfig 编译为单条 SELECT 只读度量 SQL
 * （{@code SELECT COUNT(*) FROM t ...} / {@code SELECT 利率 FROM t ...}），返回标量。
 * SCHEMA 类型返回空 SQL（需在 runner 层用 JDBC DatabaseMetaData 特殊处理，非数据 SQL）。
 *
 * <p>采样/分区（FR-008）：{@link QualityExpectation.SamplingConfig} 的 {@code partitionExpr} 拼入 WHERE、
 * {@code limit} 加 LIMIT——标注 {@code sampled=1} 避免误读全量。
 *
 * <p>依赖：Jackson 3 {@code tools.jackson.databind.ObjectMapper}（Spring Bean 注入）。
 */
@Component
public class QualityRuleCompiler {

    private final ObjectMapper om;

    public QualityRuleCompiler(ObjectMapper om) {
        this.om = om;
    }

    /**
     * @param assertionType   断言类型
     * @param expectationJson 期望参数 JSON
     * @param datasetRef      "datasourceId:schema.table"
     * @param samplingJson    采样策略 JSON（可空=FULL）
     * @return 编译结果（measureSql + 期望比较器）
     */
    public CompiledRule compile(AssertionType assertionType, String expectationJson,
                                String datasetRef, String samplingJson) {
        QualityExpectation expectation = QualityExpectation.fromJson(assertionType, expectationJson, om);
        QualityExpectation.SamplingConfig sampling = samplingJson != null && !samplingJson.isBlank()
                ? parseSampling(samplingJson) : new QualityExpectation.SamplingConfig("FULL", null, null, null);
        String table = parseTable(datasetRef);
        String sql = compileSql(assertionType, expectation, table, sampling);
        return new CompiledRule(sql, expectation, sampling, table);
    }

    /** 编译结果：度量 SQL + 期望比较器 + 采样配置 + 表名（用于 SCHEMA 检查 / 日志）。 */
    public record CompiledRule(String measureSql, QualityExpectation expectation,
                                QualityExpectation.SamplingConfig sampling, String table) {
    }

    // ---- 类型→SQL ----

    private String compileSql(AssertionType type, QualityExpectation exp, String table,
                              QualityExpectation.SamplingConfig sampling) {
        String where = samplingWhere(sampling);
        String limit = samplingLimit(sampling);
        return switch (type) {
            case ROW_COUNT -> {
                QualityExpectation.RowCount e = (QualityExpectation.RowCount) exp;
                yield "SELECT COUNT(*) FROM " + table + wherePart(where) + limitSuffix(limit);
            }
            case NULL_RATE -> {
                QualityExpectation.NullRate e = (QualityExpectation.NullRate) exp;
                yield "SELECT SUM(CASE WHEN " + q(e.column()) + " IS NULL THEN 1 ELSE 0 END)*1.0/COUNT(*)"
                        + " FROM " + table + wherePart(where) + limitSuffix(limit);
            }
            case UNIQUENESS -> {
                QualityExpectation.Uniqueness e = (QualityExpectation.Uniqueness) exp;
                String cols = e.columns().stream().map(this::q).collect(Collectors.joining(", "));
                yield "SELECT COUNT(*) - COUNT(DISTINCT " + cols + ") FROM " + table
                        + wherePart(where) + limitSuffix(limit);
            }
            case FRESHNESS -> {
                QualityExpectation.Freshness e = (QualityExpectation.Freshness) exp;
                // 返回滞后秒数 = UNIX_TIMESTAMP(NOW()) - UNIX_TIMESTAMP(MAX(ts_col))
                yield "SELECT "
                        + "CAST(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MAX(" + q(e.tsColumn()) + "))) AS BIGINT)"
                        + " FROM " + table + wherePart(where) + limitSuffix(limit);
            }
            case RANGE -> {
                QualityExpectation.Range e = (QualityExpectation.Range) exp;
                StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM ").append(table);
                sb.append(wherePart(where));
                if (e.min() != null) {
                    sb.append(where.isEmpty() ? " WHERE " : " AND ");
                    sb.append(q(e.column())).append(" < ").append(e.min());
                }
                if (e.max() != null) {
                    sb.append(where.isEmpty() && e.min() == null ? " WHERE " : " AND ");
                    sb.append(q(e.column())).append(" > ").append(e.max());
                }
                yield sb.toString() + limitSuffix(limit);
            }
            case REFERENTIAL -> {
                QualityExpectation.Referential e = (QualityExpectation.Referential) exp;
                yield "SELECT COUNT(*) FROM " + table + " a LEFT JOIN " + q(e.refTable())
                        + " b ON a." + q(e.column()) + " = b." + q(e.refColumn())
                        + " WHERE a." + q(e.column()) + " IS NOT NULL AND b." + q(e.refColumn()) + " IS NULL"
                        + wherePart(where) + limitSuffix(limit);
            }
            case CUSTOM_SQL -> {
                QualityExpectation.CustomSql e = (QualityExpectation.CustomSql) exp;
                yield e.sql(); // 用户 SQL，只读（D5 安全解析）
            }
            case SCHEMA -> ""; // SCHEMA 不走 SQL，在 runner 层干 DatabaseMetaData 对比
        };
    }

    // ---- 辅助 ----

    private String q(String id) {
        if (id == null) return id;
        return id.contains(" ") || id.contains("(") ? id : "\"" + id + "\"";
    }

    private String parseTable(String datasetRef) {
        if (datasetRef == null) return null;
        int colon = datasetRef.indexOf(':');
        return colon >= 0 ? datasetRef.substring(colon + 1) : datasetRef;
    }

    private String wherePart(String where) {
        return where.isEmpty() ? "" : " WHERE " + where;
    }

    private String limitSuffix(String limit) {
        return limit.isEmpty() ? "" : " " + limit;
    }

    private String samplingWhere(QualityExpectation.SamplingConfig s) {
        if (s.partitionExpr() != null && !s.partitionExpr().isBlank()) {
            return s.partitionExpr();
        }
        return "";
    }

    private String samplingLimit(QualityExpectation.SamplingConfig s) {
        if (s.limit() != null && s.limit() > 0) {
            return "LIMIT " + s.limit();
        }
        if (s.samplePct() != null && s.samplePct() > 0 && s.samplePct() < 100) {
            return "LIMIT " + Math.max(1, s.samplePct() * 10); // 10% → 约 LIMIT 100（粗估，方言差异）
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private QualityExpectation.SamplingConfig parseSampling(String json) {
        try {
            Map<String, Object> m = om.readValue(json, Map.class);
            String mode = (String) m.getOrDefault("mode", "FULL");
            String part = (String) m.get("partitionExpr");
            Integer pct = m.get("samplePct") instanceof Number n ? n.intValue() : null;
            Integer lim = m.get("limit") instanceof Number n ? n.intValue() : null;
            return new QualityExpectation.SamplingConfig(mode, part, pct, lim);
        } catch (Exception e) {
            return new QualityExpectation.SamplingConfig("FULL", null, null, null);
        }
    }
}
