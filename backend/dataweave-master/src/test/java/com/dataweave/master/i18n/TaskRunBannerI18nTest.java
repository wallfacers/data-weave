package com.dataweave.master.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * 验证任务运行日志 banner（taskrun.banner.*）的中英渲染 + MessageFormat 插值 + null locale 兜底。
 *
 * <p>对应 task-run-decouple-and-log-tabs design.md:60 / tasks.md 2.6：banner 按触发者 locale 渲染
 * （i18n 规则②）。外层 ==== 装饰由 {@code InProcessTaskExecutionGateway} 代码拼接（跨语言一致），
 * 此处只验标题文字与 label 的本地化，含中文全角括号「（）」vs 英文半角括号「()」。
 */
class TaskRunBannerI18nTest {

    private Messages messages() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return new Messages(ms);
    }

    @Test
    void startBanner_chineseFullWidthParens() {
        Messages m = messages();
        assertThat(m.get("taskrun.banner.start.title", Locale.SIMPLIFIED_CHINESE)).isEqualTo("DataWeave 任务运行");
        assertThat(m.get("taskrun.banner.start.line", Locale.SIMPLIFIED_CHINESE, "NORMAL", "SHELL", "-"))
                .isEqualTo("运行模式: NORMAL | 类型: SHELL | 数据源: -");
        assertThat(m.get("taskrun.banner.start.time", Locale.SIMPLIFIED_CHINESE, "2026-06-22 16:20:22"))
                .isEqualTo("开始时间: 2026-06-22 16:20:22");
        // 数据源「名称（typeCode）」——中文全角括号
        assertThat(m.get("taskrun.banner.start.datasource_fmt", Locale.SIMPLIFIED_CHINESE, "主库", "mysql"))
                .isEqualTo("主库（mysql）");
        assertThat(m.get("taskrun.banner.start.datasource_none", Locale.SIMPLIFIED_CHINESE)).isEqualTo("-");
    }

    @Test
    void startBanner_englishHalfWidthParens() {
        Messages m = messages();
        assertThat(m.get("taskrun.banner.start.title", Locale.US)).isEqualTo("DataWeave Task Run");
        assertThat(m.get("taskrun.banner.start.line", Locale.US, "NORMAL", "SHELL", "-"))
                .isEqualTo("Run mode: NORMAL | Type: SHELL | Datasource: -");
        assertThat(m.get("taskrun.banner.start.time", Locale.US, "2026-06-22 16:20:22"))
                .isEqualTo("Start time: 2026-06-22 16:20:22");
        // 数据源「name (typeCode)」——英文半角括号
        assertThat(m.get("taskrun.banner.start.datasource_fmt", Locale.US, "primary", "mysql"))
                .isEqualTo("primary (mysql)");
        assertThat(m.get("taskrun.banner.start.datasource_none", Locale.US)).isEqualTo("-");
    }

    @Test
    void endBanner_statusAndLineByLocale() {
        Messages m = messages();
        // 中文：标题 + 三态状态 + 复合行
        assertThat(m.get("taskrun.banner.end.title", Locale.SIMPLIFIED_CHINESE)).isEqualTo("执行结束");
        assertThat(m.get("taskrun.banner.status.success", Locale.SIMPLIFIED_CHINESE)).isEqualTo("成功");
        assertThat(m.get("taskrun.banner.status.failed", Locale.SIMPLIFIED_CHINESE)).isEqualTo("失败");
        assertThat(m.get("taskrun.banner.status.timeout", Locale.SIMPLIFIED_CHINESE)).isEqualTo("超时终止");
        assertThat(m.get("taskrun.banner.end.line", Locale.SIMPLIFIED_CHINESE, "成功", 0))
                .isEqualTo("状态: 成功 | 退出码: 0");
        assertThat(m.get("taskrun.banner.end.duration", Locale.SIMPLIFIED_CHINESE, "2m 30s"))
                .isEqualTo("执行耗时: 2m 30s");
        assertThat(m.get("taskrun.banner.end.time", Locale.SIMPLIFIED_CHINESE, "2026-06-22 16:20:22"))
                .isEqualTo("结束时间: 2026-06-22 16:20:22");

        // 英文：标题 + 三态状态 + 复合行
        assertThat(m.get("taskrun.banner.end.title", Locale.US)).isEqualTo("Execution Finished");
        assertThat(m.get("taskrun.banner.status.success", Locale.US)).isEqualTo("Success");
        assertThat(m.get("taskrun.banner.status.failed", Locale.US)).isEqualTo("Failed");
        assertThat(m.get("taskrun.banner.status.timeout", Locale.US)).isEqualTo("Timed out");
        assertThat(m.get("taskrun.banner.end.line", Locale.US, "Failed", 1))
                .isEqualTo("Status: Failed | Exit code: 1");
        assertThat(m.get("taskrun.banner.end.duration", Locale.US, "1h 5m 30s"))
                .isEqualTo("Duration: 1h 5m 30s");
    }

    @Test
    void nullLocale_fallsBackToChinese() {
        // InProcessTaskExecutionGateway 对 cmd.locale()==null 用 Messages.DEFAULT_LOCALE（中文）兜底；
        // 无 locale 重载 get(code) 同样走默认中文，与此兜底语义一致。
        Messages m = messages();
        assertThat(m.get("taskrun.banner.start.title")).isEqualTo("DataWeave 任务运行");
        assertThat(m.get("taskrun.banner.end.title")).isEqualTo("执行结束");
        assertThat(m.get("taskrun.banner.status.success")).isEqualTo("成功");
    }
}
