package com.dataweave.api.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;

/**
 * Stream C 专用 i18n 消息源：从 dataweave-api 自有资源加载，不依赖 dataweave-master 的 Messages bean。
 *
 * <p>键命名空间：ops.* / mcp.*，资源文件位于 {@code classpath:ops-messages*.properties}。
 * 回退链：请求 locale → en-US → zh-CN（默认）。
 */
@Component
public class OpsMessages {

    private static final Logger log = LoggerFactory.getLogger(OpsMessages.class);

    public static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    private final Properties zhCN = new Properties();
    private final Properties enUS = new Properties();

    public OpsMessages() {
        load("ops-messages.properties", zhCN);
        load("ops-messages_en_US.properties", enUS);
    }

    private void load(String resource, Properties props) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is != null) {
                props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            log.warn("Failed to load ops i18n resource: {}", resource, e);
        }
    }

    /** 按 locale 获取消息（MessageFormat 占位符 {0} {1} ...）。 */
    public String get(String key, Locale locale, Object... args) {
        Locale loc = locale != null ? locale : DEFAULT_LOCALE;
        String pattern = null;

        if ("en".equalsIgnoreCase(loc.getLanguage())) {
            pattern = enUS.getProperty(key);
        }
        // 回退中文
        if (pattern == null) {
            pattern = zhCN.getProperty(key);
        }
        if (pattern == null) {
            log.warn("Missing ops i18n key: {}", key);
            return key; // fallback: 返回 key 本身
        }
        if (args.length == 0) {
            return pattern;
        }
        try {
            return MessageFormat.format(pattern, args);
        } catch (IllegalArgumentException e) {
            log.warn("MessageFormat error for key={} pattern={}", key, pattern, e);
            return pattern;
        }
    }
}
