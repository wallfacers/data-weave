package com.dataweave.master.infrastructure;

import java.util.TimeZone;

/**
 * JVM 默认时区引导（所有进程入口在 Spring 上下文启动前调用）。
 *
 * <p>统一 {@code LocalDateTime.now()} 与 {@code ZoneId.systemDefault()} 的行为，
 * 覆盖 api / worker / localrun 三个 {@code main} 入口，避免不同部署环境时区漂移。
 *
 * <p>时区来源优先级（不写死，全部可配）：
 * <ol>
 *   <li>JVM 参数 {@code -Duser.timezone=<zone>}</li>
 *   <li>环境变量 {@code APP_TIMEZONE=<zone>}（Docker/K8s 首选）</li>
 *   <li>默认 {@code UTC}（服务端标准）</li>
 * </ol>
 *
 * <p>注意：本引导必须在上下文启动前运行，故只能读系统属性/环境变量，
 * 不能读 {@code application.yml}——yaml 里不放时区键以免造成"看似可配实则失效"的诱导。
 */
public final class TimezoneBootstrap {

    /** 未显式配置时的兜底时区（服务端标准 UTC，带 Z 的 ISO 输出）。 */
    public static final String DEFAULT_TIMEZONE = "UTC";

    private TimezoneBootstrap() {
    }

    /** 按优先级解析并设置 JVM 默认时区；幂等，可在每个 {@code main} 首行调用。 */
    public static void init() {
        String tz = System.getProperty("user.timezone");
        if (tz == null || tz.isBlank()) {
            tz = System.getenv("APP_TIMEZONE");
        }
        if (tz == null || tz.isBlank()) {
            tz = DEFAULT_TIMEZONE;
        }
        TimeZone.setDefault(TimeZone.getTimeZone(tz));
        System.setProperty("user.timezone", tz);
    }
}
