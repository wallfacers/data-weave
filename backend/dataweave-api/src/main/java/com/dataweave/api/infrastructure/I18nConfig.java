package com.dataweave.api.infrastructure;

import com.dataweave.master.i18n.Messages;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * 国际化配置 —— {@link MessageSource}（classpath:messages*.properties）+ {@link Messages} 包装 bean。
 *
 * <p>basename 文件置于 master 模块 resources，各模块经 classpath 共享。默认 locale 中文，
 * 缺失 key fallback 中文 base，最终回退 code。不 fallback 到系统 locale（避免 JVM locale 干扰）。
 *
 * <p>UI / Agent locale 的协商由 {@link Locales} 从请求头解析，不依赖 Spring {@code LocaleResolver} bean。
 */
@Configuration
public class I18nConfig {

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return ms;
    }

    @Bean
    public Messages messages(MessageSource messageSource) {
        return new Messages(messageSource);
    }
}
