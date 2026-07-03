package com.dataweave.master.application.lineage.script;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dataweave.master.application.SqlColumnLineageExtractor;
import com.dataweave.master.application.SqlTableExtractor;
import com.dataweave.master.application.lineage.ColumnLineageCatalog;
import com.dataweave.master.application.lineage.ColumnLineageResult;
import com.dataweave.master.domain.lineage.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 内嵌 SQL 抽取器（041 US1，SCRIPT_SQL 通道，research D1）。
 *
 * <p>轻量词法扫描提取字符串字面量（Python 四种引号形态 / Shell 引号 + heredoc），
 * 首关键词嗅探 SQL 后复用 Calcite 链路（{@link SqlTableExtractor} 表级 +
 * {@link SqlColumnLineageExtractor} 列级）。宁缺毋滥：f-string/变量插值 SQL →
 * DYNAMIC_SQL 提示；解析出的表名含动态占位 → 丢该表 + DYNAMIC_TABLE 提示（FR-006）。
 *
 * <p>已知边界（测试语料固化）：仅打印/记录且带前缀文案的 SQL 因嗅探失败天然不误报；
 * 纯 SQL 文本仅赋值未执行的场景会计入（静态无法区分，按确定边处理）。
 */
@Component
public class EmbeddedSqlExtractor implements ScriptLineageExtractor {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedSqlExtractor.class);

    private static final Set<String> SQL_HEAD_KEYWORDS = Set.of(
            "INSERT", "SELECT", "CREATE", "MERGE", "UPDATE", "DELETE", "WITH", "REPLACE");

    /** shell 变量：${VAR} 或 $VAR。 */
    private static final Pattern SHELL_VAR = Pattern.compile("\\$\\{[^}]*}|\\$[A-Za-z_][A-Za-z0-9_]*");
    /** python printf 占位：%s/%d/%f。 */
    private static final Pattern PY_PLACEHOLDER = Pattern.compile("%[sdf]");
    /** 动态占位标识（解析后出现在表名中 → 该表不可信）。 */
    private static final String DYN = "__dyn__";

    private final SqlTableExtractor tableExtractor;
    private final SqlColumnLineageExtractor columnExtractor;
    private final ColumnLineageCatalog catalog;

    @org.springframework.beans.factory.annotation.Autowired
    public EmbeddedSqlExtractor(SqlTableExtractor tableExtractor,
                                SqlColumnLineageExtractor columnExtractor,
                                ObjectProvider<ColumnLineageCatalog> catalog) {
        this(tableExtractor, columnExtractor, catalog.getIfAvailable());
    }

    /** 测试用便捷构造（无 catalog → 列级全降级）。 */
    public EmbeddedSqlExtractor(SqlTableExtractor tableExtractor,
                                SqlColumnLineageExtractor columnExtractor) {
        this(tableExtractor, columnExtractor, (ColumnLineageCatalog) null);
    }

    private EmbeddedSqlExtractor(SqlTableExtractor tableExtractor,
                                 SqlColumnLineageExtractor columnExtractor,
                                 ColumnLineageCatalog catalog) {
        this.tableExtractor = tableExtractor;
        this.columnExtractor = columnExtractor;
        this.catalog = catalog;
    }

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
            log.warn("[EmbeddedSql] extract degraded (FR-005): {}", e.toString());
            return new ScriptExtraction(Set.of(), Set.of(), List.of(),
                    List.of(new ScriptExtraction.Hint(ScriptExtraction.HintKind.PARSE_FAIL, 0,
                            abbreviate(e.toString()))),
                    Source.SCRIPT_SQL, null);
        }
    }

    private ScriptExtraction doExtract(ScriptSource source) {
        String content = source.content();
        if (content == null || content.isBlank()) {
            return ScriptExtraction.empty(Source.SCRIPT_SQL);
        }
        String type = source.taskType() == null ? "" : source.taskType().toUpperCase(Locale.ROOT);
        List<Literal> literals = new ArrayList<>();
        if ("PYTHON".equals(type) || "SPARK".equals(type)) {
            literals.addAll(scanPython(content));
        }
        if ("SHELL".equals(type) || "SPARK".equals(type)) {
            literals.addAll(scanShell(content));
        }

        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        List<ScriptExtraction.Hint> hints = new ArrayList<>();
        List<String> parsableSql = new ArrayList<>();

        for (Literal lit : literals) {
            String sql = lit.text().strip();
            if (!sniffSql(sql)) {
                continue;
            }
            if (lit.interpolated()) {
                hints.add(new ScriptExtraction.Hint(ScriptExtraction.HintKind.DYNAMIC_SQL,
                        lit.line(), abbreviate(sql)));
                continue;
            }
            // 占位符预处理：shell 变量 → __dyn__；python %s → NULL
            boolean hadVar = false;
            Matcher vm = SHELL_VAR.matcher(sql);
            if (vm.find()) {
                hadVar = true;
                sql = vm.replaceAll(DYN);
            }
            sql = PY_PLACEHOLDER.matcher(sql).replaceAll("NULL");

            SqlTableExtractor.Result parsed = tableExtractor.extract(sql);
            if (!parsed.parsed()) {
                hints.add(new ScriptExtraction.Hint(
                        hadVar ? ScriptExtraction.HintKind.DYNAMIC_SQL : ScriptExtraction.HintKind.PARSE_FAIL,
                        lit.line(), abbreviate(lit.text().strip())));
                continue;
            }
            boolean droppedDyn = false;
            for (String r : parsed.reads()) {
                if (containsDyn(r)) {
                    droppedDyn = true;
                } else {
                    reads.add(r);
                }
            }
            for (String w : parsed.writes()) {
                if (containsDyn(w)) {
                    droppedDyn = true;
                } else {
                    writes.add(w);
                }
            }
            if (droppedDyn) {
                hints.add(new ScriptExtraction.Hint(ScriptExtraction.HintKind.DYNAMIC_TABLE,
                        lit.line(), abbreviate(lit.text().strip())));
            }
            if (!hadVar) {
                parsableSql.add(sql);
            }
        }

        // 列级（尽力而为）：可完整解析的片段合并一次跑 Calcite 列血缘
        List<com.dataweave.master.application.lineage.ColumnEdge> columnEdges = List.of();
        if (!parsableSql.isEmpty()) {
            try {
                ColumnLineageResult col = columnExtractor.extract(String.join(";\n", parsableSql),
                        catalog, source.tenantId(), source.projectId());
                columnEdges = col.edges();
            } catch (Exception e) {
                log.debug("[EmbeddedSql] column-level degraded: {}", e.toString());
            }
        }
        return new ScriptExtraction(reads, writes, columnEdges, hints, Source.SCRIPT_SQL, null);
    }

    private static boolean containsDyn(String table) {
        return table != null && table.toLowerCase(Locale.ROOT).contains(DYN);
    }

    private static boolean sniffSql(String s) {
        if (s == null || s.length() < 8) {
            return false;
        }
        int i = 0;
        while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == '(')) {
            i++;
        }
        int j = i;
        while (j < s.length() && Character.isLetter(s.charAt(j))) {
            j++;
        }
        return SQL_HEAD_KEYWORDS.contains(s.substring(i, j).toUpperCase(Locale.ROOT));
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() > 200 ? one.substring(0, 200) : one;
    }

    /** 字面量：文本 + 起始行（1-based）+ 是否含插值（f-string / {} 占位）。 */
    private record Literal(String text, int line, boolean interpolated) {}

    // ── Python 词法：'…' "…" '''…''' """…"""，跳注释，f 前缀/花括号插值标记 ──
    static List<Literal> scanPython(String src) {
        List<Literal> out = new ArrayList<>();
        int i = 0;
        int line = 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c == '#') { // 注释到行尾
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                boolean fPrefix = hasFPrefix(src, i);
                String delim = delimiterAt(src, i, c);
                int bodyStart = i + delim.length();
                int end = findStringEnd(src, bodyStart, delim);
                if (end < 0) { // 未闭合：容错跳出
                    break;
                }
                String body = src.substring(bodyStart, end);
                boolean interpolated = fPrefix && body.contains("{");
                out.add(new Literal(unescape(body), line, interpolated));
                line += countNl(src, i, end + delim.length());
                i = end + delim.length();
                continue;
            }
            i++;
        }
        return out;
    }

    // ── Shell 词法：'…'（无转义）"…"（\ 转义）+ heredoc，跳注释 ──
    static List<Literal> scanShell(String src) {
        List<Literal> out = new ArrayList<>();
        int i = 0;
        int line = 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c == '#' && (i == 0 || Character.isWhitespace(src.charAt(i - 1)))) {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '<' && i + 1 < n && src.charAt(i + 1) == '<') { // heredoc
                int j = i + 2;
                if (j < n && src.charAt(j) == '-') {
                    j++;
                }
                while (j < n && (src.charAt(j) == ' ' || src.charAt(j) == '\'' || src.charAt(j) == '"')) {
                    j++;
                }
                int tagStart = j;
                while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_')) {
                    j++;
                }
                String tag = src.substring(tagStart, j);
                if (!tag.isEmpty()) {
                    int nl = src.indexOf('\n', j);
                    if (nl < 0) {
                        break;
                    }
                    int bodyStart = nl + 1;
                    int bodyLine = line + 1;
                    int k = bodyStart;
                    int bodyEnd = -1;
                    while (k <= n) {
                        int eol = src.indexOf('\n', k);
                        String lineText = (eol < 0 ? src.substring(k) : src.substring(k, eol)).strip();
                        if (lineText.equals(tag)) {
                            bodyEnd = k;
                            break;
                        }
                        if (eol < 0) {
                            break;
                        }
                        k = eol + 1;
                    }
                    if (bodyEnd >= 0) {
                        String body = src.substring(bodyStart, bodyEnd);
                        // heredoc 内多语句逐条切分（分号），保 sniff 命中每条
                        int subLine = bodyLine;
                        for (String stmt : body.split(";")) {
                            if (!stmt.isBlank()) {
                                out.add(new Literal(stmt, subLine, false));
                            }
                            subLine += countNl(stmt, 0, stmt.length());
                        }
                        line = bodyLine + countNl(src, bodyStart, bodyEnd) + 1;
                        i = bodyEnd + tag.length();
                        continue;
                    }
                }
            }
            if (c == '\'' || c == '"') {
                int end = c == '\''
                        ? src.indexOf('\'', i + 1)
                        : findStringEnd(src, i + 1, "\"");
                if (end < 0) {
                    break;
                }
                String body = src.substring(i + 1, end);
                out.add(new Literal(c == '"' ? unescape(body) : body, line, false));
                line += countNl(src, i, end + 1);
                i = end + 1;
                continue;
            }
            i++;
        }
        return out;
    }

    private static boolean hasFPrefix(String src, int quotePos) {
        int p = quotePos - 1;
        int seen = 0;
        while (p >= 0 && Character.isLetter(src.charAt(p)) && seen < 3) {
            if (src.charAt(p) == 'f' || src.charAt(p) == 'F') {
                return true;
            }
            p--;
            seen++;
        }
        return false;
    }

    private static String delimiterAt(String src, int i, char quote) {
        if (i + 2 < src.length() && src.charAt(i + 1) == quote && src.charAt(i + 2) == quote) {
            return String.valueOf(quote).repeat(3);
        }
        return String.valueOf(quote);
    }

    /** 从 start 起找 delim 结束位置（支持 \ 转义；三引号内单引号合法）。 */
    private static int findStringEnd(String src, int start, String delim) {
        int i = start;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (src.startsWith(delim, i)) {
                return i;
            }
            // 单行字符串不跨行（python/shell 双引号里裸换行非法，容错收在行尾）
            if (delim.length() == 1 && c == '\n' && !delim.equals("\"")) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", " ").replace("\\\"", "\"").replace("\\'", "'");
    }

    private static int countNl(String s, int from, int to) {
        int c = 0;
        for (int i = Math.max(0, from); i < Math.min(s.length(), to); i++) {
            if (s.charAt(i) == '\n') {
                c++;
            }
        }
        return c;
    }
}
