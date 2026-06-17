package com.dataweave.api.infrastructure;

import com.dataweave.master.i18n.Messages;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;

/**
 * 请求头 locale 解析 helper —— UI locale 与 Agent locale 双通道。
 *
 * <ul>
 *   <li><b>UI locale</b>：从 {@code Accept-Language} 解析（用于错误 message、REST 响应本地化）。</li>
 *   <li><b>Agent locale</b>：从 {@code x-dw-agent-locale} 解析，缺失 fallback 到 UI locale，再 fallback zh-CN
 *       （用于 AG-UI markdown 回复、MCP 工具描述）。</li>
 * </ul>
 *
 * <p>REST 层为 Spring MVC（servlet），故核心方法以 header 字符串入参；另提供 {@link HttpHeaders} 重载
 * 供 reactive 场景（AG-UI SSE）复用。直接读请求头而非依赖 Spring {@code LocaleResolver} bean，便于测试控制。
 */
public final class Locales {

    /** Agent locale 请求头名。 */
    public static final String AGENT_LOCALE_HEADER = "x-dw-agent-locale";

    private static final List<Locale> SUPPORTED = List.of(Locale.US, Locale.SIMPLIFIED_CHINESE);

    private Locales() {
    }

    /** 从 Accept-Language 头字符串解析 UI locale；不支持或缺失时 fallback 中文。 */
    public static Locale uiLocale(String acceptLanguage) {
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
            if (!ranges.isEmpty()) {
                Locale resolved = Locale.lookup(ranges, SUPPORTED);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return Messages.DEFAULT_LOCALE;
    }

    /** 从 HttpHeaders 解析 UI locale（reactive 场景重载）。 */
    public static Locale uiLocale(HttpHeaders headers) {
        return uiLocale(headers.getFirst("Accept-Language"));
    }

    /** 从 agent 头 + Accept-Language 解析 Agent locale；agent 头缺失 fallback UI locale，再中文。 */
    public static Locale agentLocale(String agentHeader, String acceptLanguage) {
        if (agentHeader != null && !agentHeader.isBlank()) {
            return parse(agentHeader);
        }
        return uiLocale(acceptLanguage);
    }

    /** 从 HttpHeaders 解析 Agent locale（reactive 场景重载）。 */
    public static Locale agentLocale(HttpHeaders headers) {
        return agentLocale(headers.getFirst(AGENT_LOCALE_HEADER), headers.getFirst("Accept-Language"));
    }

    /** 解析 locale tag（支持 zh-CN / en-US / zh / en），未知一律中文。 */
    public static Locale parse(String tag) {
        if (tag == null || tag.isBlank()) {
            return Messages.DEFAULT_LOCALE;
        }
        String t = tag.trim().toLowerCase().replace("_", "-");
        return t.startsWith("en") ? Locale.US : Messages.DEFAULT_LOCALE;
    }
}
