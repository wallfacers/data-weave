package com.dataweave.master.i18n;

import java.util.Locale;
import org.springframework.context.MessageSource;

/**
 * 国际化文案统一出口 —— 业务代码一律经此获取本地化字符串，不直接 {@code @Autowired} {@link MessageSource}。
 *
 * <p>解析链：{@code messages_<locale>} → {@code messages}（中文 base）→ code 本身。
 * 任一环节缺失都不抛 {@code NoSuchMessageException}，最终回退为 code 字符串。
 *
 * <p>本类置于 master 模块，使 master / api（及 all-in-one 下的 worker）均可取用；
 * 调用方负责传入正确的 locale（UI 场景用 Accept-Language 解析的 locale，Agent 场景用
 * {@code x-dw-agent-locale} 解析的 locale）。
 */
public class Messages {

    /** 默认 locale（中文简体），亦是 fallback 终点。 */
    public static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    private final MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 按 locale 解析文案；缺失时 fallback 到中文 base，再缺失回退为 code 本身。 */
    public String get(String code, Locale locale, Object... args) {
        return messageSource.getMessage(code, args, code, locale);
    }

    /** 按默认 locale（中文）解析。 */
    public String get(String code, Object... args) {
        return get(code, DEFAULT_LOCALE, args);
    }
}
