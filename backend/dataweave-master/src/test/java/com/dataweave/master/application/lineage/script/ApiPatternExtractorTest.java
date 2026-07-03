package com.dataweave.master.application.lineage.script;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 041 US2 语料库单测（T011）：规则推断通道 表级召回 ≥80% / 错报 <10%（SC-002 可执行化）；
 * 字段静态可枚举形态出字段级边且正确率 ≥90%（Q2 裁决）。
 */
class ApiPatternExtractorTest {

    private final ApiPatternExtractor extractor = new ApiPatternExtractor();

    record Case(String id, String taskType, String script,
                Set<String> reads, Set<String> writes,
                Set<String> writeColumns, ScriptExtraction.HintKind hint) {}

    private static final List<Case> CORPUS = List.of(
            // ── 写接口正例 ──
            new Case("save-as-table", "PYTHON",
                    "df.write.mode(\"overwrite\").saveAsTable(\"dw.users_clean\")",
                    Set.of(), Set.of("dw.users_clean"), Set.of(), null),
            new Case("insert-into", "PYTHON",
                    "src.write.insertInto(\"dw.orders\", overwrite=True)",
                    Set.of(), Set.of("dw.orders"), Set.of(), null),
            new Case("to-sql", "PYTHON",
                    "df.to_sql(\"user_summary\", engine, if_exists=\"append\", index=False)",
                    Set.of(), Set.of("user_summary"), Set.of(), null),
            new Case("to-sql-single-quote", "PYTHON",
                    "df.to_sql('mart.kpi_daily', con)",
                    Set.of(), Set.of("mart.kpi_daily"), Set.of(), null),
            // ── 读接口正例 ──
            new Case("spark-read-table", "PYTHON",
                    "df = spark.read.table(\"ods.users\")",
                    Set.of("ods.users"), Set.of(), Set.of(), null),
            new Case("read-sql-table-name", "PYTHON",
                    "df = pd.read_sql(\"member_points\", con)",
                    Set.of("member_points"), Set.of(), Set.of(), null),
            new Case("read-sql-select-skipped", "PYTHON",
                    "df = pd.read_sql(\"SELECT a FROM ods.x\", con)",
                    Set.of(), Set.of(), Set.of(), null),   // SQL 形态归内嵌 SQL 通道，规则通道不重复出边
            // ── 读写组合 ──
            new Case("read-then-save", "PYTHON",
                    "df = spark.read.table(\"ods.events\")\ndf2 = df.filter(df.ok)\ndf2.write.saveAsTable(\"dws.events_clean\")",
                    Set.of("ods.events"), Set.of("dws.events_clean"), Set.of(), null),
            // ── 字段级正例（显式列清单）──
            new Case("column-list-to-sql", "PYTHON",
                    "df = spark.read.table(\"ods.users\").toPandas()\ndf[[\"id\", \"name\"]].to_sql(\"dw.users_slim\", engine)",
                    Set.of("ods.users"), Set.of("dw.users_slim"), Set.of("id", "name"), null),
            new Case("column-list-single-quote", "PYTHON",
                    "src = spark.read.table(\"ods.pay\").toPandas()\nsrc[['uid', 'amt']].to_sql('dw.pay_slim', engine)",
                    Set.of("ods.pay"), Set.of("dw.pay_slim"), Set.of("uid", "amt"), null),
            // ── sqoop CLI ──
            new Case("sqoop-export", "SHELL",
                    "sqoop export --connect \"$JDBC\" --table warehouse_out --export-dir /stage",
                    Set.of(), Set.of("warehouse_out"), Set.of(), null),
            // sqoop 源是 HDFS 目录而非表：列级"边"无 src 端点不可成立，只验收表级写边（--columns 仅当
            // 脚本内存在唯一可归因读表时才成边——见 column-list 两例；FR-003 的字段级承诺以边可成立为前提）
            new Case("sqoop-export-columns", "SHELL",
                    "sqoop export --connect \"$JDBC\" --table dw_kpi --columns d,v --export-dir /stage",
                    Set.of(), Set.of("dw_kpi"), Set.of(), null),
            new Case("sqoop-import", "SHELL",
                    "sqoop import --connect \"$JDBC\" --table src_orders --target-dir /land",
                    Set.of("src_orders"), Set.of(), Set.of(), null),
            // ── 动态目标 → 提示不猜边 ──
            new Case("dynamic-fstring", "PYTHON",
                    "tbl = f\"dw.tmp_{ds}\"\ndf.write.saveAsTable(tbl)",
                    Set.of(), Set.of(), Set.of(), ScriptExtraction.HintKind.DYNAMIC_TABLE),
            new Case("dynamic-fstring-inline", "PYTHON",
                    "df.to_sql(f\"rpt_{month}\", engine)",
                    Set.of(), Set.of(), Set.of(), ScriptExtraction.HintKind.DYNAMIC_TABLE),
            new Case("dynamic-var-arg", "PYTHON",
                    "target = config[\"table\"]\ndf.write.insertInto(target)",
                    Set.of(), Set.of(), Set.of(), ScriptExtraction.HintKind.DYNAMIC_TABLE),
            new Case("sqoop-dynamic", "SHELL",
                    "sqoop export --connect \"$JDBC\" --table ads_rpt_${DT} --export-dir /s",
                    Set.of(), Set.of(), Set.of(), ScriptExtraction.HintKind.DYNAMIC_TABLE),
            // ── 负例：无读写 ──
            new Case("pure-pandas", "PYTHON",
                    "df2 = df.groupby(\"k\").sum()\nprint(df2.head())",
                    Set.of(), Set.of(), Set.of(), null),
            new Case("pure-shell", "SHELL",
                    "set -e\necho start\nmkdir -p /tmp/x",
                    Set.of(), Set.of(), Set.of(), null),
            new Case("mention-in-comment", "PYTHON",
                    "# df.write.saveAsTable(\"dw.old\") 已废弃\nx = 1",
                    Set.of(), Set.of(), Set.of(), null),
            new Case("string-not-call", "PYTHON",
                    "doc = \"use saveAsTable to persist\"\nprint(doc)",
                    Set.of(), Set.of(), Set.of(), null));

    @Test
    void tableRecallAtLeast80AndFalseReportBelow10Percent() {
        int expectedEdges = 0;
        int recalled = 0;
        int reported = 0;
        int falseReported = 0;
        StringBuilder detail = new StringBuilder();
        for (Case c : CORPUS) {
            ScriptExtraction ex = extractor.extract(src(c));
            expectedEdges += c.reads().size() + c.writes().size();
            for (String r : c.reads()) {
                if (ex.reads().contains(r)) {
                    recalled++;
                }
            }
            for (String w : c.writes()) {
                if (ex.writes().contains(w)) {
                    recalled++;
                }
            }
            reported += ex.reads().size() + ex.writes().size();
            for (String r : ex.reads()) {
                if (!c.reads().contains(r)) {
                    falseReported++;
                    detail.append("\n  [").append(c.id()).append("] 错报读 ").append(r);
                }
            }
            for (String w : ex.writes()) {
                if (!c.writes().contains(w)) {
                    falseReported++;
                    detail.append("\n  [").append(c.id()).append("] 错报写 ").append(w);
                }
            }
            if (c.hint() != null) {
                assertThat(ex.hints()).as("[%s] 应产生 %s 提示", c.id(), c.hint())
                        .anyMatch(h -> h.kind() == c.hint());
            }
        }
        double recall = expectedEdges == 0 ? 1.0 : recalled * 1.0 / expectedEdges;
        double falseRate = reported == 0 ? 0.0 : falseReported * 1.0 / reported;
        assertThat(recall).as("SC-002 表级召回（%d/%d）%s", recalled, expectedEdges, detail)
                .isGreaterThanOrEqualTo(0.80);
        assertThat(falseRate).as("SC-002 错报率（%d/%d）%s", falseReported, reported, detail)
                .isLessThan(0.10);
    }

    @Test
    void columnLevelAccuracyAtLeast90PercentOnEnumerableForms() {
        int total = 0;
        int correct = 0;
        for (Case c : CORPUS) {
            if (c.writeColumns().isEmpty()) {
                continue;
            }
            total++;
            ScriptExtraction ex = extractor.extract(src(c));
            Set<String> gotCols = new java.util.HashSet<>();
            ex.columnEdges().forEach(ce -> gotCols.add(ce.dstCol()));
            if (gotCols.equals(c.writeColumns())) {
                correct++;
            }
        }
        assertThat(total).isGreaterThanOrEqualTo(2);
        assertThat(correct * 1.0 / total).as("字段级正确率（%d/%d）", correct, total)
                .isGreaterThanOrEqualTo(0.90);
    }

    @Test
    void channelIsScriptInferred() {
        ScriptExtraction ex = extractor.extract(new ScriptSource(1, 1, 9L, "PYTHON",
                "df.write.saveAsTable(\"dw.t\")", null, null));
        assertThat(ex.channel()).isEqualTo(com.dataweave.master.domain.lineage.Source.SCRIPT_INFERRED);
    }

    @Test
    void neverThrows() {
        for (String garbage : new String[]{null, "", "df.to_sql(", "sqoop export --table"}) {
            assertThat(extractor.extract(new ScriptSource(1, 1, 9L, "PYTHON", garbage, null, null)))
                    .isNotNull();
        }
    }

    private static ScriptSource src(Case c) {
        return new ScriptSource(1, 1, 9L, c.taskType(), c.script(), null, null);
    }
}
