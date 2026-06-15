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

/**
 * 调度参数占位符解析器（变更 scheduling-parameters，design D1–D7）。
 *
 * <p>在 {@code SchedulerKernel} 下发 {@code content} 执行前，把模板里的占位符替换为具体值。
 * 替换是纯函数：只依赖入参（{@code bizDate}、{@code paramsJson}、{@link BuiltInContext}），
 * 无副作用、可独立单测。
 *
 * <h3>支持</h3>
 * <ul>
 *   <li>业务日期语法 {@code ${<fmt>}}：基于 {@code bizDate}（T-1，天精度）格式化，
 *       token 仅 {@code y/m/d}（如 {@code yyyymmdd}、{@code yyyy-mm-dd}、{@code yyyymm}）。</li>
 *   <li>整数偏移 {@code ${<fmt>±N}}：单位取 fmt 最小单位（dd→天 / mm→月 / yyyy→年），
 *       周用 {@code ${yyyymmdd-7*N}}。</li>
 *   <li>系统内置参数：{@code $bizdate}、{@code $bizmonth}（跨月特判）、{@code $gmtdate}、
 *       {@code $jobid}、{@code $nodeid}、{@code $taskid}。</li>
 *   <li>自定义参数递归展开：{@code ${name}} → {@code paramsJson} 中 {@code name} 的值；
 *       值若仍含 {@code ${...}} 继续递归，带访问栈做循环检测。</li>
 * </ul>
 *
 * <h3>不支持（抛 {@link UnresolvedPlaceholderException}）</h3>
 * <ul>
 *   <li>天精度以外的 token（{@code hh/mi/ss}）。</li>
 *   <li>字面嵌套 {@code ${${...}}}（占位符内部又出现 {@code ${}）。</li>
 *   <li>未定义的 {@code ${name}}（既非日期格式也非自定义参数）。</li>
 * </ul>
 *
 * <p>非内置的裸 {@code $word}（如 shell 变量 {@code $HOME}）原样保留，不替换。
 * 无任何 {@code $} 的 {@code content} 走快速路径原样返回（no-op）。
 */
@Component
public final class ScheduleParamResolver {

    /** 内置参数标识 + 当前日期（用于 {@code $gmtdate} / {@code $bizmonth} 跨月判断，调用方传入以保证可测）。 */
    public record BuiltInContext(String jobId, String nodeId, String taskInstanceId, LocalDate today) {
    }

    /** 占位符无法解析时抛出，message 携带占位符名，供上层转 {@code FAILED} + {@code errorMessage}。 */
    public static final class UnresolvedPlaceholderException extends RuntimeException {
        public UnresolvedPlaceholderException(String message) {
            super(message);
        }
    }

    private static final Pattern OFFSET = Pattern.compile("^([-+])(\\d+)(?:\\*(\\d+))?$");

    /**
     * 解析 {@code content} 里的所有占位符。无占位符或入参为空时原样返回。
     *
     * @param content    任务内容模板（SQL / Shell）
     * @param bizDate    业务日期（{@code yyyy-MM-dd} 或 {@code yyyyMMdd}，T-1）
     * @param paramsJson 自定义参数 JSON（{@code {"name":"expr"}}），可为空
     * @param ctx        内置参数标识 + 当前日期
     * @return 替换后的 {@code content}
     * @throws UnresolvedPlaceholderException 任一占位符无法解析时
     */
    public String resolve(String content, String bizDate, String paramsJson, BuiltInContext ctx) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        if (content.indexOf('$') < 0) {
            return content;  // 快速路径：无 $ 不解析
        }
        LocalDate biz = parseBizDate(bizDate);
        Map<String, String> params = parseParams(paramsJson);
        return resolveText(content, biz, params, ctx, new LinkedHashSet<>());
    }

    /** 递归解析文本：扫描 {@code $} 起始的占位符，其余字符原样输出。 */
    private String resolveText(String text, LocalDate biz, Map<String, String> params,
                               BuiltInContext ctx, Set<String> stack) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= n) {
                out.append('$');
                break;
            }
            char next = text.charAt(i + 1);
            if (next == '{') {
                // ${...}：扫描到匹配的 }，中间出现 { 视为非法嵌套
                int end = -1;
                for (int j = i + 2; j < n; j++) {
                    char cj = text.charAt(j);
                    if (cj == '{') {
                        throw new UnresolvedPlaceholderException(
                                "非法嵌套占位符（不支持 ${${...}}），位置 " + i);
                    }
                    if (cj == '}') {
                        end = j;
                        break;
                    }
                }
                if (end < 0) {
                    throw new UnresolvedPlaceholderException("未闭合的 ${ 占位符，位置 " + i);
                }
                String expr = text.substring(i + 2, end).trim();
                if (expr.isEmpty()) {
                    throw new UnresolvedPlaceholderException("空占位符 ${}");
                }
                out.append(resolveExpr(expr, biz, params, ctx, stack));
                i = end + 1;
            } else if (Character.isLetter(next)) {
                // $word：读标识符；内置词替换，否则原样保留（可能是 shell 变量）
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                    j++;
                }
                String word = text.substring(i + 1, j);
                String builtin = builtIn(word, biz, ctx);
                if (builtin != null) {
                    out.append(builtin);
                } else {
                    out.append('$').append(word);
                }
                i = j;
            } else {
                // $ 后非 { 非字母（如 $$、$1）：原样保留
                out.append('$');
                i++;
            }
        }
        return out.toString();
    }

    /** 解析单个 {@code ${expr}}：先试日期表达式，失败则查自定义参数（递归），都不行则报错。 */
    private String resolveExpr(String expr, LocalDate biz, Map<String, String> params,
                               BuiltInContext ctx, Set<String> stack) {
        String dateVal = tryDateExpr(expr, biz);
        if (dateVal != null) {
            return dateVal;
        }
        if (params.containsKey(expr)) {
            if (!stack.add(expr)) {
                throw new UnresolvedPlaceholderException("循环引用参数：" + chain(stack, expr));
            }
            String val = params.get(expr);
            String expanded = resolveText(val == null ? "" : val, biz, params, ctx, stack);
            stack.remove(expr);
            return expanded;
        }
        throw new UnresolvedPlaceholderException("未定义占位符 ${" + expr + "}");
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
            throw new UnresolvedPlaceholderException("非法偏移表达式（末尾悬空运算符）：" + expr);
        }

        long offset = 0;
        if (offsetPart != null) {
            Matcher m = OFFSET.matcher(offsetPart);
            if (!m.matches()) {
                throw new UnresolvedPlaceholderException("非法偏移表达式：" + expr);
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

    /** 系统内置参数；非内置返回 {@code null}（调用方原样保留 {@code $word}）。 */
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

    /** {@code $bizmonth}：业务日期月份 == 当前月份时取上月，否则取业务日期月份，格式 {@code yyyyMM}。 */
    private String bizMonth(LocalDate biz, BuiltInContext ctx) {
        YearMonth bizYM = YearMonth.from(biz);
        YearMonth todayYM = ctx.today() != null ? YearMonth.from(ctx.today()) : bizYM;
        YearMonth result = bizYM.equals(todayYM) ? bizYM.minusMonths(1) : bizYM;
        return String.format("%04d%02d", result.getYear(), result.getMonthValue());
    }

    private LocalDate parseBizDate(String bizDate) {
        if (bizDate == null || bizDate.isBlank()) {
            throw new UnresolvedPlaceholderException("bizDate 为空，无法解析日期占位符");
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
            throw new UnresolvedPlaceholderException("非法 bizDate：" + bizDate);
        }
        throw new UnresolvedPlaceholderException("非法 bizDate（需 yyyy-MM-dd 或 yyyyMMdd）：" + bizDate);
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
