package com.dataweave.master.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * 验证 i18n 文案解析链：中文默认、英文按 locale、缺失 fallback 中文、最终回退 code。
 * 对应 internationalization spec「双语覆盖与 fallback」requirement。
 */
class MessagesTest {

    private Messages messages() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return new Messages(ms);
    }

    @Test
    void shouldResolveChineseByDefault() {
        assertThat(messages().get("common.success")).isEqualTo("成功");
    }

    @Test
    void shouldResolveEnglishForUsLocale() {
        assertThat(messages().get("common.success", Locale.US)).isEqualTo("Success");
    }

    @Test
    void shouldFallbackToChineseBaseWhenEnKeyMissing() {
        // test.fallback_only_zh 仅中文 bundle 收录
        assertThat(messages().get("test.fallback_only_zh", Locale.US)).isEqualTo("仅中文");
    }

    @Test
    void shouldFallbackToCodeWhenKeyMissingEverywhere() {
        assertThat(messages().get("nonexistent.key", Locale.US)).isEqualTo("nonexistent.key");
    }

    @Test
    void shouldInterpolateMessageFormatArgs() {
        assertThat(messages().get("approval.not_found", Locale.SIMPLIFIED_CHINESE, "#42"))
                .isEqualTo("未找到审批单 #42");
        assertThat(messages().get("approval.not_found", Locale.US, "#42"))
                .isEqualTo("Approval #42 not found");
    }
}
