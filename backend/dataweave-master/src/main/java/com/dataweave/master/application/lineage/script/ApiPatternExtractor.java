package com.dataweave.master.application.lineage.script;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dataweave.master.application.lineage.ColumnEdge;
import com.dataweave.master.application.lineage.Confidence;
import com.dataweave.master.application.lineage.TableRef;
import com.dataweave.master.application.lineage.Transform;
import com.dataweave.master.domain.lineage.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 程序接口读写表的规则推断抽取器（041 US2，SCRIPT_INFERRED 通道，research D2）。
 *
 * <p>模式常量表识别常见读写接口调用与 sqoop CLI 形态；表名必须是字符串常量——
 * f-string/变量实参 → DYNAMIC_TABLE 提示不猜边（FR-006 宁缺毋滥）。字段级：
 * 显式列清单（{@code df[["a","b"]].to_sql(…)}、{@code sqoop --columns a,b}）可静态枚举时
 * 出字段级边（Q2 裁决）；列边需 src+dst 成对，仅在"单一读表"可归因时产生（启发式，UNVERIFIED）。
 */
@Component
public class ApiPatternExtractor implements ScriptLineageExtractor {

    private static final Logger log = LoggerFactory.getLogger(ApiPatternExtractor.class);

    /** 写接口：.saveAsTable("t") / .insertInto("t") / .to_sql("t", …)。 */
    private static final Pattern WRITE_CALL = Pattern.compile(
            "\\.(saveAsTable|insertInto|to_sql)\\(\\s*(['\"])([^'\"]+)\\2");
    /** 写接口动态实参：f-string 或标识符（非字符串常量）。 */
    private static final Pattern WRITE_CALL_DYNAMIC = Pattern.compile(
            "\\.(saveAsTable|insertInto|to_sql)\\(\\s*(?:[fF]['\"]|[A-Za-z_][A-Za-z0-9_\\[\\]]*\\s*[,)])");
    /** 读接口：spark.read.table("t") / read_sql("t"|SQL, con)。 */
    private static final Pattern READ_TABLE = Pattern.compile(
            "\\.read\\.table\\(\\s*(['\"])([^'\"]+)\\1");
    private static final Pattern READ_SQL = Pattern.compile(
            "\\bread_sql\\(\\s*(['\"])([^'\"]+)\\1");
    /** 显式列清单：df[["a","b"]].to_sql(…) 同链。 */
    private static final Pattern COLUMN_LIST_TO_SQL = Pattern.compile(
            "\\[\\[([^\\]]+)]]\\s*\\.to_sql\\(\\s*(['\"])([^'\"]+)\\2");
    /** sqoop 形态。 */
    private static final Pattern SQOOP = Pattern.compile("\\bsqoop\\s+(import|export)\\b([^\\n]*)");
    private static final Pattern SQOOP_TABLE = Pattern.compile("--table\\s+(\\S+)");
    private static final Pattern SQOOP_COLUMNS = Pattern.compile("--columns\\s+([\\w,]+)");
    /** SQL 首关键词（read_sql 传 SQL 时归内嵌 SQL 通道，规则不重复出边）。 */
    private static final Pattern SQL_HEAD = Pattern.compile(
            "^\\s*(SELECT|INSERT|WITH|CREATE|MERGE|UPDATE|DELETE)\\b", Pattern.CASE_INSENSITIVE);
    /** 合法静态表名。 */
    private static final Pattern TABLE_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$");

    @Override
    public boolean supports(String taskType) {
        if (taskType == null) {
            return false;
        }
        String t = taskType.toUpperCase(Locale.ROOT);
        return "PYTHON".equals(t) || "SHELL".equals(t) || "SPARK".equals(t);
    }

    @Override
    public ScriptExtraction extract(ScriptSource source) {
        try {
            return doExtract(source);
        } catch (Exception e) {
            log.warn("[ApiPattern] extract degraded (FR-005): {}", e.toString());
            return ScriptExtraction.empty(Source.SCRIPT_INFERRED);
        }
    }

    private ScriptExtraction doExtract(ScriptSource source) {
        String content = source.content();
        if (content == null || content.isBlank()) {
            return ScriptExtraction.empty(Source.SCRIPT_INFERRED);
        }
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        List<ScriptExtraction.Hint> hints = new ArrayList<>();
        List<String[]> columnWrites = new ArrayList<>();   // [table, col1, col2…]

        String[] lines = content.split("\n", -1);
        for (int ln = 0; ln < lines.length; ln++) {
            String raw = lines[ln];
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            int lineNo = ln + 1;

            Matcher colList = COLUMN_LIST_TO_SQL.matcher(line);
            while (colList.find()) {
                String table = colList.group(3);
                if (acceptTable(table, writes, hints, lineNo, raw)) {
                    List<String> cols = new ArrayList<>();
                    cols.add(table);
                    for (String c : colList.group(1).split(",")) {
                        String col = c.strip().replaceAll("^['\"]|['\"]$", "");
                        if (!col.isBlank()) {
                            cols.add(col);
                        }
                    }
                    columnWrites.add(cols.toArray(String[]::new));
                }
            }

            Matcher w = WRITE_CALL.matcher(line);
            while (w.find()) {
                acceptTable(w.group(3), writes, hints, lineNo, raw);
            }
            // 动态实参（避开已匹配常量的情形：常量已被 WRITE_CALL 消费不会再命中此分支的引号形态）
            Matcher wd = WRITE_CALL_DYNAMIC.matcher(line);
            while (wd.find()) {
                hints.add(new ScriptExtraction.Hint(ScriptExtraction.HintKind.DYNAMIC_TABLE,
                        lineNo, abbreviate(raw)));
            }

            Matcher rt = READ_TABLE.matcher(line);
            while (rt.find()) {
                acceptTable(rt.group(2), reads, hints, lineNo, raw);
            }
            Matcher rs = READ_SQL.matcher(line);
            while (rs.find()) {
                String arg = rs.group(2);
                if (!SQL_HEAD.matcher(arg).find()) {
                    acceptTable(arg, reads, hints, lineNo, raw);
                }
            }

            Matcher sq = SQOOP.matcher(line);
            while (sq.find()) {
                boolean isImport = "import".equals(sq.group(1));
                String rest = sq.group(2);
                Matcher tm = SQOOP_TABLE.matcher(rest);
                if (tm.find()) {
                    String table = tm.group(1);
                    Set<String> target = isImport ? reads : writes;
                    if (acceptTable(table, target, hints, lineNo, raw) && !isImport) {
                        Matcher cm = SQOOP_COLUMNS.matcher(rest);
                        if (cm.find()) {
                            List<String> cols = new ArrayList<>();
                            cols.add(table);
                            for (String c : cm.group(1).split(",")) {
                                if (!c.isBlank()) {
                                    cols.add(c.strip());
                                }
                            }
                            columnWrites.add(cols.toArray(String[]::new));
                        }
                    }
                }
            }
        }

        // 字段级：仅单一读表可归因时成边（同名列映射启发式，UNVERIFIED）
        List<ColumnEdge> columnEdges = new ArrayList<>();
        if (reads.size() == 1 && !columnWrites.isEmpty()) {
            String srcTable = reads.iterator().next();
            for (String[] cw : columnWrites) {
                for (int i = 1; i < cw.length; i++) {
                    columnEdges.add(new ColumnEdge(
                            TableRef.of(srcTable), cw[i],
                            TableRef.of(cw[0]), cw[i],
                            Transform.DIRECT, Confidence.UNVERIFIED));
                }
            }
        }
        return new ScriptExtraction(reads, writes, columnEdges, hints, Source.SCRIPT_INFERRED, null);
    }

    /** 表名为合法静态标识 → 收下返回 true；否则 DYNAMIC_TABLE 提示返回 false。 */
    private static boolean acceptTable(String table, Set<String> target,
                                       List<ScriptExtraction.Hint> hints, int line, String raw) {
        if (table != null && TABLE_NAME.matcher(table).matches()) {
            target.add(table);
            return true;
        }
        hints.add(new ScriptExtraction.Hint(ScriptExtraction.HintKind.DYNAMIC_TABLE, line, abbreviate(raw)));
        return false;
    }

    /** 去行内注释：python/shell 的 #（忽略引号内 # 的少见情形——注释误伤比误报边代价低）。 */
    private static String stripComment(String line) {
        int idx = -1;
        boolean inS = false;
        boolean inD = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inD) {
                inS = !inS;
            } else if (c == '"' && !inS) {
                inD = !inD;
            } else if (c == '#' && !inS && !inD) {
                idx = i;
                break;
            }
        }
        return idx < 0 ? line : line.substring(0, idx);
    }

    private static String abbreviate(String s) {
        String one = s == null ? "" : s.strip();
        return one.length() > 200 ? one.substring(0, 200) : one;
    }
}
