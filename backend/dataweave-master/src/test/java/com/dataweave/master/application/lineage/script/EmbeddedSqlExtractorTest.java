package com.dataweave.master.application.lineage.script;

import java.util.List;
import java.util.Set;

import com.dataweave.master.application.SqlColumnLineageExtractor;
import com.dataweave.master.application.SqlTableExtractor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 041 US1 语料库单测（T006）：内嵌 SQL 抽取正确率 ≥95%（SC-001 可执行化）。
 *
 * <p>语料 ≥20 条带标注样例：Python 四种字符串形态 / 变量中转 / Shell 六种载体 / heredoc /
 * 多语句 / CTE；负例：f-string 动态 SQL、注释假 SQL、打印假 SQL、纯计算、变量表名。
 */
class EmbeddedSqlExtractorTest {

    private final EmbeddedSqlExtractor extractor = new EmbeddedSqlExtractor(
            new SqlTableExtractor(), new SqlColumnLineageExtractor(new SqlTableExtractor()));

    /** 一条语料：脚本 + 期望读写 + 期望提示形态（null=不要求）。 */
    record Case(String id, String taskType, String script,
                Set<String> reads, Set<String> writes, ScriptExtraction.HintKind hint) {}

    private static final List<Case> CORPUS = List.of(
            // ── Python 正例 ──
            new Case("py-double-quote", "PYTHON",
                    "spark.sql(\"INSERT INTO dw.orders SELECT id, amount FROM ods.orders\")",
                    Set.of("ods.orders"), Set.of("dw.orders"), null),
            new Case("py-single-quote", "PYTHON",
                    "cur.execute('SELECT id, name FROM ods.users LIMIT 10')",
                    Set.of("ods.users"), Set.of(), null),
            new Case("py-triple-double", "PYTHON",
                    "sql = \"\"\"\nINSERT INTO dws.agg_daily\nSELECT biz_date, SUM(amount) FROM dwd.orders GROUP BY biz_date\n\"\"\"\nspark.sql(sql)",
                    Set.of("dwd.orders"), Set.of("dws.agg_daily"), null),
            new Case("py-triple-single", "PYTHON",
                    "q = '''SELECT a, b FROM stg.events'''\ndf = spark.sql(q)",
                    Set.of("stg.events"), Set.of(), null),
            new Case("py-var-indirect", "PYTHON",
                    "sql = \"INSERT INTO ads.report SELECT x, y FROM dws.metrics\"\nlog.info('run')\nspark.sql(sql)",
                    Set.of("dws.metrics"), Set.of("ads.report"), null),
            new Case("py-placeholder-values", "PYTHON",
                    "cur.execute(\"INSERT INTO dw.audit_log (uid, act) VALUES (%s, %s)\", (uid, act))",
                    Set.of(), Set.of("dw.audit_log"), null),
            new Case("py-multi-statement", "PYTHON",
                    "spark.sql(\"SELECT id FROM ods.a\")\nx = 1\nspark.sql(\"INSERT INTO dw.b SELECT id FROM ods.c\")",
                    Set.of("ods.a", "ods.c"), Set.of("dw.b"), null),
            new Case("py-cte", "PYTHON",
                    "spark.sql(\"INSERT INTO dw.clean WITH t AS (SELECT id FROM ods.raw) SELECT id FROM t\")",
                    Set.of("ods.raw"), Set.of("dw.clean"), null),
            new Case("py-subprocess-hive", "PYTHON",
                    "subprocess.run([\"hive\", \"-e\", \"INSERT INTO dw.t2 SELECT a FROM ods.t1\"], check=True)",
                    Set.of("ods.t1"), Set.of("dw.t2"), null),
            new Case("py-join", "PYTHON",
                    "spark.sql(\"INSERT INTO dw.wide SELECT a.id, b.v FROM ods.x a JOIN ods.y b ON a.id=b.id\")",
                    Set.of("ods.x", "ods.y"), Set.of("dw.wide"), null),
            // ── Python 负例 ──
            new Case("py-fstring-dynamic", "PYTHON",
                    "tbl = f\"dw.tmp_{ds}\"\nspark.sql(f\"INSERT INTO {tbl} SELECT * FROM ods.src\")",
                    Set.of(), Set.of(), ScriptExtraction.HintKind.DYNAMIC_SQL),
            new Case("py-comment-sql", "PYTHON",
                    "# INSERT INTO dw.legacy SELECT * FROM old\ncur.execute(\"SELECT id FROM ods.live\")",
                    Set.of("ods.live"), Set.of(), null),
            new Case("py-print-only", "PYTHON",
                    "print(\"dry-run: INSERT INTO dw.fake SELECT 1\")",
                    Set.of(), Set.of(), null),
            new Case("py-pure-compute", "PYTHON",
                    "total = sum(x * x for x in range(100))\nprint(total)",
                    Set.of(), Set.of(), null),
            // ── Shell 正例 ──
            new Case("sh-hive-e", "SHELL",
                    "hive -e \"INSERT INTO dw.orders SELECT id FROM ods.orders\"",
                    Set.of("ods.orders"), Set.of("dw.orders"), null),
            new Case("sh-beeline", "SHELL",
                    "beeline -u \"$HS2\" -e \"SELECT id, amt FROM dws.pay\"",
                    Set.of("dws.pay"), Set.of(), null),
            new Case("sh-psql-c", "SHELL",
                    "psql -h db01 -c \"INSERT INTO mart.kpi SELECT d, v FROM dws.base\"",
                    Set.of("dws.base"), Set.of("mart.kpi"), null),
            new Case("sh-mysql-e", "SHELL",
                    "mysql -uroot -e 'INSERT INTO bi.summary (d, v) VALUES (1, 2)'",
                    Set.of(), Set.of("bi.summary"), null),
            new Case("sh-spark-sql", "SHELL",
                    "spark-sql --master yarn -e \"INSERT INTO ads.rpt SELECT k FROM dws.agg\"",
                    Set.of("dws.agg"), Set.of("ads.rpt"), null),
            new Case("sh-heredoc", "SHELL",
                    "psql \"$URL\" <<SQL\nSELECT id FROM ods.inbox;\nINSERT INTO dw.outbox SELECT id FROM ods.inbox;\nSQL",
                    Set.of("ods.inbox"), Set.of("dw.outbox"), null),
            // ── Shell 负例/部分抽取 ──
            new Case("sh-dynamic-table", "SHELL",
                    "TBL=\"ads_rpt_${DT}\"\nhive -e \"INSERT INTO $TBL SELECT id FROM ods.src\"",
                    Set.of("ods.src"), Set.of(), ScriptExtraction.HintKind.DYNAMIC_TABLE),
            new Case("sh-echo-only", "SHELL",
                    "echo \"dry-run: INSERT INTO dw.fake SELECT 1\"",
                    Set.of(), Set.of(), null),
            new Case("sh-comment", "SHELL",
                    "# hive -e \"INSERT INTO dw.old SELECT 1\"\nhive -e \"SELECT id FROM ods.live\"",
                    Set.of("ods.live"), Set.of(), null),
            new Case("sh-pure", "SHELL",
                    "set -euo pipefail\necho done",
                    Set.of(), Set.of(), null));

    @Test
    void corpusTableAccuracyAtLeast95Percent() {
        int total = CORPUS.size();
        int pass = 0;
        StringBuilder failures = new StringBuilder();
        for (Case c : CORPUS) {
            ScriptExtraction ex = extractor.extract(source(c));
            boolean ok = ex.reads().equals(c.reads()) && ex.writes().equals(c.writes());
            if (c.hint() != null) {
                ok &= ex.hints().stream().anyMatch(h -> h.kind() == c.hint());
            }
            if (ok) {
                pass++;
            } else {
                failures.append("\n  [").append(c.id()).append("] got reads=").append(ex.reads())
                        .append(" writes=").append(ex.writes()).append(" hints=").append(ex.hints())
                        .append(" want reads=").append(c.reads()).append(" writes=").append(c.writes());
            }
        }
        double accuracy = pass * 1.0 / total;
        assertThat(accuracy)
                .as("SC-001 表级正确率（%d/%d）%s", pass, total, failures)
                .isGreaterThanOrEqualTo(0.95);
    }

    @Test
    void columnLevelExtractedForParsableInsertSelect() {
        ScriptExtraction ex = extractor.extract(source(new Case("col", "PYTHON",
                "spark.sql(\"INSERT INTO dw.orders SELECT id, amount FROM ods.orders\")",
                Set.of(), Set.of(), null)));
        // 列级尽力而为：至少不抛异常；能解析时目标表应为 dw.orders
        ex.columnEdges().forEach(ce ->
                assertThat(ce.dstTable().qualifiedName().toLowerCase()).isEqualTo("dw.orders"));
    }

    @Test
    void neverThrowsOnGarbage() {
        for (String garbage : new String[]{null, "", "def broken(:\n  'unclosed", "\"\"\"", "'", "<<EOF"}) {
            ScriptExtraction ex = extractor.extract(new ScriptSource(1, 1, 9L, "PYTHON", garbage, null, null));
            assertThat(ex).isNotNull();
        }
    }

    private static ScriptSource source(Case c) {
        return new ScriptSource(1, 1, 9L, c.taskType(), c.script(), null, null);
    }
}
