package com.dataweave.master.quality.application;

import com.dataweave.master.quality.domain.AssertionType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

/**
 * 外部化的断言期望参数 record（供 QualityRuleCompiler 解析 expectationJson）。
 * 每种 {@link AssertionType} 对应一个子 record，由 {@link #fromJson(AssertionType, String, ObjectMapper)} 创建。
 */
sealed interface QualityExpectation permits
        QualityExpectation.RowCount,
        QualityExpectation.NullRate,
        QualityExpectation.Uniqueness,
        QualityExpectation.Freshness,
        QualityExpectation.Range,
        QualityExpectation.Referential,
        QualityExpectation.CustomSql,
        QualityExpectation.Schema_ {

    /** 从 JSON 反序列化，按 type 校验结构。 */
    static QualityExpectation fromJson(AssertionType type, String json, ObjectMapper om) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("expectation_json 为空");
        }
        try {
            return switch (type) {
                case ROW_COUNT -> om.readValue(json, RowCount.class);
                case NULL_RATE -> om.readValue(json, NullRate.class);
                case UNIQUENESS -> om.readValue(json, Uniqueness.class);
                case FRESHNESS -> om.readValue(json, Freshness.class);
                case RANGE -> om.readValue(json, Range.class);
                case REFERENTIAL -> om.readValue(json, Referential.class);
                case CUSTOM_SQL -> om.readValue(json, CustomSql.class);
                case SCHEMA -> om.readValue(json, Schema_.class);
            };
        } catch (Exception e) {
            throw new IllegalArgumentException("expectation_json 不匹配 assertionType=" + type + "：" + e.getMessage(), e);
        }
    }

    /** 将 measured_value 字符串与期望比较，返回 PASS/FAIL 及期望描述。 */
    QualityVerdict evaluate(String measuredValue);

    /** 编译为期望描述字符串（如 "≥1000" / "≤0.01" / "=0"）。 */
    String expectedDescription();

    record RowCount(Long min, Long max, Long delta, String partitionExpr) implements QualityExpectation {
        @Override
        public QualityVerdict evaluate(String mv) {
            if (mv == null) return QualityVerdict.fail("0", expectedDescription(), "measured=null");
            long cnt = Long.parseLong(mv);
            if (min != null && cnt < min) return QualityVerdict.fail(mv, expectedDescription(), "低于下限");
            if (max != null && cnt > max) return QualityVerdict.fail(mv, expectedDescription(), "高于上限");
            return QualityVerdict.pass(mv, expectedDescription());
        }
        @Override
        public String expectedDescription() {
            StringBuilder sb = new StringBuilder();
            if (min != null) sb.append("≥").append(min);
            if (max != null) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append("≤").append(max);
            }
            return !sb.isEmpty() ? sb.toString() : "—";
        }
    }

    record NullRate(String column, Double max) implements QualityExpectation {
        @Override
        public QualityVerdict evaluate(String mv) {
            if (mv == null) return QualityVerdict.fail("0", expectedDescription(), "measured=null");
            double rate = Double.parseDouble(mv);
            if (max != null && rate > max) return QualityVerdict.fail(mv, expectedDescription(), "空值率超标");
            return QualityVerdict.pass(mv, expectedDescription());
        }
        @Override
        public String expectedDescription() { return "≤" + max; }
    }

    record Uniqueness(List<String> columns) implements QualityExpectation {
        @Override
        public QualityVerdict evaluate(String mv) {
            if (mv == null) return QualityVerdict.fail("0", expectedDescription(), "measured=null");
            long dup = Long.parseLong(mv);
            if (dup > 0) return QualityVerdict.fail(mv, expectedDescription(), "重复行 " + dup);
            return QualityVerdict.pass(mv, expectedDescription());
        }
        @Override
        public String expectedDescription() { return "dup=0"; }
    }

    record Freshness(String tsColumn, Long maxLagSec) implements QualityExpectation {
        @Override
        public QualityVerdict evaluate(String mv) {
            if (mv == null) return QualityVerdict.fail("0", expectedDescription(), "measured=null(无可取值)");
            long lag = Long.parseLong(mv);
            if (lag > maxLagSec) {
                return QualityVerdict.fail(mv, expectedDescription(), formatLag(lag));
            }
            return QualityVerdict.pass(mv, expectedDescription());
        }
        @Override
        public String expectedDescription() { return "滞后≤" + maxLagSec + "s"; }
        private String formatLag(long s) {
            if (s < 3600) return s + "s";
            if (s < 86400) return (s / 3600) + "h" + ((s % 3600) / 60) + "m";
            return (s / 86400) + "d" + ((s % 86400) / 3600) + "h";
        }
    }

    record Range(String column, Long min, Long max) implements QualityExpectation {
        @Override
        public QualityVerdict evaluate(String mv) {
            if (mv == null) return QualityVerdict.fail("0", expectedDescription(), "measured=null");
            long violations = Long.parseLong(mv);
            if (violations > 0) return QualityVerdict.fail(mv, expectedDescription(), "越界行 " + violations);
            return QualityVerdict.pass(mv, expectedDescription());
        }
        @Override
        public String expectedDescription() {
            return "violations=0" + (min != null ? " [" + min : "") + (max != null ? "–" + max + "]" : "]");
        }
    }

    record Referential(String column, String refTable, String refColumn) implements QualityExpectation {
        @Override
        public QualityVerdict evaluate(String mv) {
            if (mv == null) return QualityVerdict.fail("0", expectedDescription(), "measured=null");
            long orphans = Long.parseLong(mv);
            if (orphans > 0) return QualityVerdict.fail(mv, expectedDescription(), "孤立行 " + orphans);
            return QualityVerdict.pass(mv, expectedDescription());
        }
        @Override
        public String expectedDescription() { return "orphans=0"; }
    }

    record CustomSql(String sql, Long expectRows) implements QualityExpectation {
        @Override
        public QualityVerdict evaluate(String mv) {
            if (mv == null) return QualityVerdict.fail("0", expectedDescription(), "measured=null");
            long rows = Long.parseLong(mv);
            long expected = expectRows != null ? expectRows : 0;
            if (rows > expected) return QualityVerdict.fail(mv, expectedDescription(), "违规行 " + rows);
            return QualityVerdict.pass(mv, expectedDescription());
        }
        @Override
        public String expectedDescription() { return "违规行≤" + (expectRows != null ? expectRows : 0); }
    }

    /** SCHEMA 断言：经 JDBC DatabaseMetaData 对比（非数据 SQL），measured_value 存差异描述。 */
    record Schema_(List<ColumnDef> expectedColumns, boolean strict) implements QualityExpectation {
        public record ColumnDef(String name, String type) {}

        @Override
        public QualityVerdict evaluate(String mv) {
            if (mv == null || mv.isBlank()) return QualityVerdict.pass(mv, expectedDescription());
            return QualityVerdict.fail(mv, expectedDescription(), "schema 差异：" + mv);
        }
        @Override
        public String expectedDescription() {
            return "列数=" + expectedColumns.size() + (strict ? " strict" : " flexible");
        }
    }

    /** 断言裁决：PASS/FAIL + measured + expected + reason。 */
    record QualityVerdict(boolean pass, String measured, String expected, String reason) {
        static QualityVerdict pass(String measured, String expected) {
            return new QualityVerdict(true, measured, expected, null);
        }
        static QualityVerdict fail(String measured, String expected, String reason) {
            return new QualityVerdict(false, measured, expected, reason);
        }
    }

    /** 采样策略。 */
    record SamplingConfig(String mode, String partitionExpr, Integer samplePct, Integer limit) {
        public boolean isFull() { return mode == null || mode.equalsIgnoreCase("FULL"); }
        public boolean isSampled() { return !isFull(); }
    }
}
