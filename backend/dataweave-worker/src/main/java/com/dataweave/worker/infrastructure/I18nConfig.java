package com.dataweave.worker.infrastructure;

import com.dataweave.master.i18n.Messages;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * Worker 模块国际化配置 —— {@link MessageSource} + {@link Messages} bean。
 *
 * <p>镜像 api 模块的 I18nConfig，供 distributed worker 侧 banner 按触发者 locale 渲染。
 * basename 文件在 master 模块 classpath（各模块共享），${@code messages*.properties}。
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
