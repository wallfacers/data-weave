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
 *
 * <p>045：class bean 名改 {@code workerI18nConfig} + @Bean 方法重命名(workerMessageSource/workerMessages)，
 * 避免 api 进程（fat jar 含 worker）与 api I18nConfig 同名 class bean(i18nConfig)/@Bean(messageSource/messages)
 * 冲突（pre-existing main bug：api pom 依赖 worker + 两个 @Configuration I18nConfig 默认 bean 名撞车；
 * 044 distributed 用旧 image 未触发，045 rebuild 暴露）。worker 独立进程（fat jar 不含 api 模块）正常提供
 * MessageSource/Messages；api 进程用 api I18nConfig（@Primary）。
 */
@Configuration("workerI18nConfig")
public class I18nConfig {

    @Bean("workerMessageSource")
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return ms;
    }

    @Bean("workerMessages")
    public Messages messages(MessageSource messageSource) {
        return new Messages(messageSource);
    }
}
