package com.dataweave.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * DataWeave 后端启动入口（dataweave-api）。
 *
 * <p>扫描整个 com.dataweave 包以装配 master/worker/alert 模块的组件；
 * 显式开启 Spring Data JDBC repositories（master 模块的仓储在 com.dataweave.master.domain）。
 *
 * <p>自定义 basePackages 覆盖了 {@code @SpringBootApplication} 默认的 {@code @ComponentScan}，
 * 必须显式补回 {@link TypeExcludeFilter}（排除其他测试的 {@code @TestConfiguration}，否则跨测试泄漏）
 * 与 {@link AutoConfigurationExcludeFilter}（避免自动配置类被当普通组件重复注册）。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.dataweave", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
@EnableJdbcRepositories(basePackages = "com.dataweave.master.domain")
@EnableScheduling
@EnableAsync
public class DataWeaveApiApplication {

    public static void main(String[] args) {
        initTimezone();
        SpringApplication.run(DataWeaveApiApplication.class, args);
    }

    /**
     * 在 Spring 上下文启动前设置 JVM 默认时区，确保所有 {@code LocalDateTime.now()} 和
     * {@code ZoneId.systemDefault()} 使用统一时区。
     *
     * <p>优先级：① 系统属性 {@code user.timezone}（-D 参数）→
     * ② 环境变量 {@code APP_TIMEZONE} → ③ 默认 {@code UTC}。
     */
    private static void initTimezone() {
        String tz = System.getProperty("user.timezone");
        if (tz == null) {
            tz = System.getenv("APP_TIMEZONE");
        }
        if (tz == null) {
            tz = "UTC";
        }
        TimeZone.setDefault(TimeZone.getTimeZone(tz));
        System.setProperty("user.timezone", tz);
    }
}
