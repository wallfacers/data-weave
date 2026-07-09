package com.dataweave.master.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.dataweave.master.i18n.BizException;

/**
 * 调度参数占位符解析器（变更 scheduling-parameters，design D1–D7）。
 *
 * <p>在 {@code SchedulerKernel} 下发 {@code content} 执行前，把模板里的占位符替换为具体值。
 * 替换是纯函数：只依赖入参（{@code bizDate}、{@code paramsJson}、{@link BuiltInContext}），
 * 无副作用、可独立单测。
 *
 * <h3>平台占位符语法：{@code {{...}}}（双花括号，严格模式）</h3>
 * <p>未知 / 非法 / 未闭合的 {@code {{...}}} 一律抛 {@link UnresolvedPlaceholderException}。</p>
 *
 * <h3>Shell 风格占位符：{@code ${...}}（宽松模式，兼容 DataWorks 迁移）</h3>
 * <p>已知内置参数 / 日期表达式则解析替换；未知变量<strong>原样输出</strong>（留给 shell 处理）。
 * 其他 shell 构造（{@code $(...)}、裸 {@code $word}、{@code $$}、{@code $1} 等）同样原样输出。</p>
 *
 * <h3>支持</h3>
 * <ul>
 *   <li>业务日期语法 {@code {{<fmt>}}} / {@code ${<fmt>}}：基于 {@code bizDate}（T-1，天精度）格式化，
 *       token 仅 {@code y/m/d}（如 {@code {{yyyymmdd}}}、{@code ${yyyy-mm-dd}}、{@code {{yyyymm}}}）。</li>
 *   <li>整数偏移 {@code {{<fmt>±N}}} / {@code ${<fmt>±N}}：单位取 fmt 最小单位（dd→天 / mm→月 / yyyy→年），
 *       周用 {@code ${yyyymmdd-7*N}}。</li>
 *   <li>系统内置参数：{@code {{bizdate}}} / {@code ${bizdate}}、{@code {{bizmonth}}} / {@code ${bizmonth}}
 *       （跨月特判）、{@code {{gmtdate}}} / {@code ${gmtdate}}、{@code {{jobid}}} / {@code ${jobid}}、
 *       {@code {{nodeid}}} / {@code ${nodeid}}、{@code {{taskid}}} / {@code ${taskid}}。</li>
 *   <li>自定义参数递归展开（仅 {@code {{name}}}）：{@code paramsJson} 中 {@code name} 的值；
 *       值若仍含 {@code {{...}}} 继续递归，带访问栈做循环检测。</li>
 * </ul>
 *
 * <h3>不支持（抛 {@link UnresolvedPlaceholderException}）</h3>
 * <ul>
 *   <li>天精度以外的 token（{@code hh/mi/ss}）。</li>
 *   <li>字面嵌套 {@code {{{{...}}}}}（占位符内部又出现 {@code {{}）。</li>
 *   <li>未闭合 {@code {{x} / 空 {{}}}。</li>
 *   <li>未定义的 {@code {{name}}}（既非日期格式 / 内置词也非自定义参数）。</li>
 * </ul>
 *
 * <p>无任何 {@code {{} 且无任何 {@code ${} 的 {@code content} 走快速路径原样返回（no-op）。</p>
 */
@Component
public final class ScheduleParamResolver {

    /** 内置参数标识 + 当前日期（用于 {@code {{gmtdate}}} / {@code {{bizmonth}}} 跨月判断，调用方传入以保证可测）。 */
    public record BuiltInContext(String jobId, String nodeId, String taskInstanceId, LocalDate today) {
    }

    /**
     * 占位符无法解析时抛出。继承 {@link BizException}，构造时传入稳定 code + 插值参数，
     * 由 {@code GlobalExceptionHandler} 统一本地化展示文案；{@link #getMessage()} 返回 code 便于日志溯源。
     *
     * <p>保留为命名子类是为维持类型语义与现有 {@code isInstanceOf} 测试断言不变。
     */
    public static final class UnresolvedPlaceholderException extends BizException {
        public UnresolvedPlaceholderException(String code, Object... args) {
            super(code, args);
        }
    }

    private static final Pattern OFFSET = Pattern.compile("^([-+])(\\d+)(?:\\*(\\d+))?$");

    /**
     * 平台调度占位符开标记：{@code {{}}。用于快速判定 content 是否需要解析。
     */
    private static final String PLATFORM_PLACEHOLDER_OPEN = "{{";

    /** content 是否含平台调度占位符（{@code {{}}）；不含（含 null/空）则无需解析，放过所有 shell/SQL 构造。 */
    public static boolean hasPlatformPlaceholder(String content) {
        return content != null && !content.isEmpty() && content.contains(PLATFORM_PLACEHOLDER_OPEN);
    }

    /**
     * 解析 {@code content} 里的所有占位符。无占位符或入参为空时原样返回。
     *
     * <p>先做 shell 风格 {@code ${...}} → 平台风格 {@code {{...}}} 预转换（仅已知内置参数
     * / 日期表达式 / 自定义参数），再走正常的 {@code {{...}}} 解析。未知的 {@code ${...}}（shell 变量）
     * 原样保留。</p>
     *
     * @param content    任务内容模板（SQL / Shell）
     * @param bizDate    业务日期（{@code yyyy-MM-dd} 或 {@code yyyyMMdd}，T-1）
     * @param paramsJson 自定义参数 JSON（{@code {"name":"expr"}}），可为空
     * @param ctx        内置参数标识 + 当前日期
     * @return 替换后的 {@code content}
     * @throws UnresolvedPlaceholderException 任一 {@code {{...}}} 占位符无法解析时
     */
    public String resolve(String content, String bizDate, String paramsJson, BuiltInContext ctx) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        Map<String, String> params = null;

        // 预转换：已知的 ${expr} → {{expr}}（兼容 DataWorks 迁移）
        if (content.contains("${")) {
            params = parseParams(paramsJson);
            content = convertShellStylePlaceholders(content, params);
        }

        if (!hasPlatformPlaceholder(content)) {
            return content;  // 快速路径：无平台占位符原样返回（放过 bash $(...)、$HOME 等）
        }
        LocalDate biz = parseBizDate(bizDate);
        if (params == null) {
            params = parseParams(paramsJson);
        }
        return resolveText(content, biz, params, ctx, new LinkedHashSet<>());
    }

    /**
     * 递归解析文本：只扫描 {@code {{...}}} 平台占位符，其余字符（含 {@code $}、{@code ${}、单个 {@code {}）原样输出。
     */
    private String resolveText(String text, LocalDate biz, Map<String, String> params,
                               BuiltInContext ctx, Set<String> stack) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            // 平台占位符以 {{ 起始；其余一切（含单个 {、$、${、$( 等）原样输出
            if (c != '{' || i + 1 >= n || text.charAt(i + 1) != '{') {
                out.append(c);
                i++;
                continue;
            }
            // {{...}}：从 i+2 起扫描到匹配的 }}，中间再现 { 视为非法嵌套（如 {{{{x}}}}）
            int end = -1;
            for (int j = i + 2; j < n; j++) {
                char cj = text.charAt(j);
                if (cj == '{') {
                    throw new UnresolvedPlaceholderException("schedule.placeholder.nested", i);
                }
                if (cj == '}') {
                    if (j + 1 < n && text.charAt(j + 1) == '}') {
                        end = j;
                        break;
                    }
                    // 单个 } 不闭合双花括号 → 继续扫描（最终若无 }} 则报 unclosed）
                }
            }
            if (end < 0) {
                throw new UnresolvedPlaceholderException("schedule.placeholder.unclosed", i);
            }
            String expr = text.substring(i + 2, end).trim();
            if (expr.isEmpty()) {
                throw new UnresolvedPlaceholderException("schedule.placeholder.empty");
            }
            out.append(resolveExpr(expr, biz, params, ctx, stack));
            i = end + 2;  // 跳过结尾的 }}
        }
        return out.toString();
    }

    /**
     * 预转换：将已知的 shell 风格占位符 {@code ${expr}} 转为平台风格 {@code {{expr}}}。
     * 仅转换已知内置参数 / 日期表达式 / paramsJson 中定义的自定义参数；
     * 未知的 {@code ${expr}}（shell 变量如 {@code ${HOME}}、{@code ${VAR}}）原样保留。
     */
    private String convertShellStylePlaceholders(String content, Map<String, String> params) {
        StringBuilder out = new StringBuilder(content.length() + 16);
        int n = content.length();
        int i = 0;
        while (i < n) {
            char c = content.charAt(i);
            if (c == '$' && i + 2 < n && content.charAt(i + 1) == '{') {
                // 扫描到匹配的 }
                int end = -1;
                int depth = 1;
                for (int j = i + 2; j < n; j++) {
                    char cj = content.charAt(j);
                    if (cj == '{') {
                        depth++;
                    } else if (cj == '}') {
                        depth--;
                        if (depth == 0) {
                            end = j;
                            break;
                        }
                    }
                }
                if (end < 0 || end == i + 2) {
                    // 无闭合 } 或空 ${}→ 原样输出
                    out.append(c);
                    i++;
                    continue;
                }
                String expr = content.substring(i + 2, end).trim();
                if (expr.isEmpty() || !isConvertibleExpression(expr, params)) {
                    // 空表达式 / 未知变量 → 原样保留
                    out.append("${").append(expr).append("}");
                } else {
                    // 已知表达式 → 转为 {{canonicalExpr}}（下划线变体映射到规范名）
                    String canonical = KEYWORD_CANONICAL.getOrDefault(expr, expr);
                    out.append("{{").append(canonical).append("}}");
                }
                i = end + 1;  // 跳过 }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 内置关键词集合（含下划线变体，兼容 DataWorks 常见写法）。 */
    private static final Set<String> BUILT_IN_KEYWORDS = Set.of(
            "bizdate", "BIZ_DATE",
            "bizmonth", "BIZ_MONTH",
            "gmtdate", "GMT_DATE",
            "jobid", "JOB_ID",
            "nodeid", "NODE_ID",
            "taskid", "TASK_ID"
    );

    /** 下划线变体 → 规范内置词映射（预转换时用）。 */
    private static final Map<String, String> KEYWORD_CANONICAL = Map.of(
            "BIZ_DATE", "bizdate",
            "BIZ_MONTH", "bizmonth",
            "GMT_DATE", "gmtdate",
            "JOB_ID", "jobid",
            "NODE_ID", "nodeid",
            "TASK_ID", "taskid"
    );

    /**
     * 判断 {@code expr} 是否为可转换的已知表达式：日期格式表达式、内置关键词、或 paramsJson 中定义的自定义参数。
     */
    private boolean isConvertibleExpression(String expr, Map<String, String> params) {
        // 日期表达式：含 y/m/d token（复用 tryDateExpr 的 fmt 判断）
        if (isDateExpression(expr)) {
            return true;
        }
        // 内置关键词（含下划线变体）
        if (BUILT_IN_KEYWORDS.contains(expr)) {
            return true;
        }
        // 自定义参数
        if (params != null && params.containsKey(expr)) {
            return true;
        }
        return false;
    }

    /**
     * 判断 expr 是否可能是日期格式表达式（含 y/m/d token，不含 h/s/i 等非日期 token）。
     * 逻辑与 {@link #tryDateExpr} 一致，但不做实际格式化。
     */
    private boolean isDateExpression(String expr) {
        // 分离 fmt 与 offset：offset 起点是「后跟数字的 +/-」
        int opIdx = -1;
        for (int j = 0; j < expr.length(); j++) {
            char ch = expr.charAt(j);
            if ((ch == '+' || ch == '-') && j + 1 < expr.length() && Character.isDigit(expr.charAt(j + 1))) {
                opIdx = j;
                break;
            }
        }
        String fmt = opIdx > 0 ? expr.substring(0, opIdx) : (opIdx == 0 ? null : expr);
        if (fmt == null || fmt.isEmpty()) {
            return false;
        }
        boolean hasToken = false;
        for (int j = 0; j < fmt.length(); j++) {
            char ch = fmt.charAt(j);
            if (Character.isLetter(ch)) {
                if (ch != 'y' && ch != 'm' && ch != 'd') {
                    return false;  // 含非日期字母（如 h/s/i）→ 非日期表达式
                }
                hasToken = true;
            }
        }
        return hasToken;
    }

    /** 解析单个 {@code {{expr}}}：先试日期表达式，再试内置词，失败则查自定义参数（递归），都不行则报错。 */
    private String resolveExpr(String expr, LocalDate biz, Map<String, String> params,
                               BuiltInContext ctx, Set<String> stack) {
        String dateVal = tryDateExpr(expr, biz);
        if (dateVal != null) {
            return dateVal;
        }
        String builtin = builtIn(expr, biz, ctx);
        if (builtin != null) {
            return builtin;
        }
        if (params.containsKey(expr)) {
            if (!stack.add(expr)) {
                throw new UnresolvedPlaceholderException("schedule.param.circular", chain(stack, expr));
            }
            String val = params.get(expr);
            String expanded = resolveText(val == null ? "" : val, biz, params, ctx, stack);
            stack.remove(expr);
            return expanded;
        }
        throw new UnresolvedPlaceholderException("schedule.placeholder.undefined", expr);
    }

    /** 尝试把 {@code expr} 当日期表达式解析；非日期格式返回 {@code null}（交由参数名处理）。 */
    private String tryDateExpr(String expr, LocalDate biz) {
        // 分离 fmt 与 offset：offset 起点是「后跟数字的 +/-」
        int opIdx = -1;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if ((c == '+' || c == '-') && i + 1 < expr.length() && Character.isDigit(expr.charAt(i + 1))) {
                opIdx = i;
                break;
            }
        }
        String fmt;
        String offsetPart = null;
        if (opIdx > 0) {
            fmt = expr.substring(0, opIdx);
            offsetPart = expr.substring(opIdx);
        } else if (opIdx == 0) {
            return null;  // 以 +/- 开头，非法 fmt
        } else {
            fmt = expr;
        }

        // fmt 字母只允许 y/m/d；含 h/s/i 等则非日期表达式（交后续处理）
        boolean hasToken = false;
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (Character.isLetter(c)) {
                if (c != 'y' && c != 'm' && c != 'd') {
                    return null;
                }
                hasToken = true;
            }
        }
        if (!hasToken) {
            return null;
        }
        char last = fmt.charAt(fmt.length() - 1);
        if (last == '+' || last == '-') {
            throw new UnresolvedPlaceholderException("schedule.offset.dangling", expr);
        }

        long offset = 0;
        if (offsetPart != null) {
            Matcher m = OFFSET.matcher(offsetPart);
            if (!m.matches()) {
                throw new UnresolvedPlaceholderException("schedule.offset.illegal", expr);
            }
            long sign = "-".equals(m.group(1)) ? -1L : 1L;
            long val = Long.parseLong(m.group(2));
            long mul = m.group(3) != null ? Long.parseLong(m.group(3)) : 1L;
            offset = sign * val * mul;
        }

        LocalDate target = biz;
        if (offset != 0) {
            if (fmt.indexOf('d') >= 0) {
                target = biz.plusDays(offset);
            } else if (fmt.indexOf('m') >= 0) {
                target = biz.plusMonths(offset);
            } else {
                target = biz.plusYears(offset);
            }
        }
        return format(target, fmt);
    }

    /** 按 fmt 把日期格式化为字符串：连续 y/m/d 段按位数取值，其余字符（分隔符）原样。 */
    private String format(LocalDate date, String fmt) {
        StringBuilder out = new StringBuilder(fmt.length() + 4);
        int i = 0;
        int n = fmt.length();
        while (i < n) {
            char c = fmt.charAt(i);
            if (c == 'y' || c == 'm' || c == 'd') {
                int j = i;
                while (j < n && fmt.charAt(j) == c) {
                    j++;
                }
                int count = j - i;
                switch (c) {
                    case 'y' -> {
                        if (count <= 2) {
                            out.append(String.format("%02d", date.getYear() % 100));
                        } else {
                            out.append(String.format("%0" + count + "d", date.getYear()));
                        }
                    }
                    case 'm' -> out.append(String.format("%0" + Math.max(count, 2) + "d", date.getMonthValue()));
                    case 'd' -> out.append(String.format("%0" + Math.max(count, 2) + "d", date.getDayOfMonth()));
                }
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 系统内置参数（{@code {{bizdate}}} 等内的词）；非内置返回 {@code null}（交后续自定义参数 / undefined 处理）。 */
    private String builtIn(String word, LocalDate biz, BuiltInContext ctx) {
        return switch (word) {
            case "bizdate" -> format(biz, "yyyymmdd");
            case "bizmonth" -> bizMonth(biz, ctx);
            case "gmtdate" -> ctx.today() != null ? format(ctx.today(), "yyyymmdd") : null;
            case "jobid" -> ctx.jobId();
            case "nodeid" -> ctx.nodeId();
            case "taskid" -> ctx.taskInstanceId();
            default -> null;
        };
    }

    /** {@code {{bizmonth}}}：业务日期月份 == 当前月份时取上月，否则取业务日期月份，格式 {@code yyyyMM}。 */
    private String bizMonth(LocalDate biz, BuiltInContext ctx) {
        YearMonth bizYM = YearMonth.from(biz);
        YearMonth todayYM = ctx.today() != null ? YearMonth.from(ctx.today()) : bizYM;
        YearMonth result = bizYM.equals(todayYM) ? bizYM.minusMonths(1) : bizYM;
        return String.format("%04d%02d", result.getYear(), result.getMonthValue());
    }

    private LocalDate parseBizDate(String bizDate) {
        if (bizDate == null || bizDate.isBlank()) {
            throw new UnresolvedPlaceholderException("schedule.bizdate.empty");
        }
        String s = bizDate.trim();
        try {
            if (s.length() == 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
                return LocalDate.parse(s);
            }
            if (s.length() == 8) {
                return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
            }
        } catch (RuntimeException e) {
            throw new UnresolvedPlaceholderException("schedule.bizdate.illegal", bizDate);
        }
        throw new UnresolvedPlaceholderException("schedule.bizdate.format", bizDate);
    }

    /** 解析 {@code paramsJson}（{@code {"name":"expr"}}）为 Map；非对象/空/非法 JSON 视作空 Map（容错）。 */
    private Map<String, String> parseParams(String paramsJson) {
        Map<String, String> m = new LinkedHashMap<>();
        if (paramsJson == null) {
            return m;
        }
        String s = paramsJson.trim();
        if (s.isEmpty() || !s.startsWith("{")) {
            return m;
        }
        int i = skipWs(s, 1);
        if (i < s.length() && s.charAt(i) == '}') {
            return m;
        }
        while (i < s.length()) {
            i = skipWs(s, i);
            if (i >= s.length() || s.charAt(i) != '"') {
                break;
            }
            int[] p = new int[1];
            String key = readString(s, i, p);
            i = p[0];
            i = skipWs(s, i);
            if (i >= s.length() || s.charAt(i) != ':') {
                break;
            }
            i = skipWs(s, i + 1);
            String val;
            if (i < s.length() && s.charAt(i) == '"') {
                val = readString(s, i, p);
                i = p[0];
            } else {
                int j = i;
                while (j < s.length() && s.charAt(j) != ',' && s.charAt(j) != '}') {
                    j++;
                }
                val = s.substring(i, j).trim();
            }
            m.put(key, val);
            i = skipWs(s, i);
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            break;
        }
        return m;
    }

    /** 从 {@code s[i]}（指向开引号）读取转义字符串，返回值并经 {@code out[0]} 回传下一个下标。 */
    private String readString(String s, int i, int[] out) {
        StringBuilder sb = new StringBuilder();
        i++;  // skip opening quote
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char e = s.charAt(i + 1);
                sb.append(switch (e) {
                    case '"' -> "\"";
                    case '\\' -> "\\";
                    case '/' -> "/";
                    case 'n' -> "\n";
                    case 't' -> "\t";
                    case 'r' -> "\r";
                    default -> String.valueOf(e);
                });
                i += 2;
                continue;
            }
            if (c == '"') {
                out[0] = i + 1;
                return sb.toString();
            }
            sb.append(c);
            i++;
        }
        out[0] = i;
        return sb.toString();
    }

    private int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private String chain(Set<String> stack, String next) {
        return String.join(" -> ", stack) + " -> " + next;
    }
}
