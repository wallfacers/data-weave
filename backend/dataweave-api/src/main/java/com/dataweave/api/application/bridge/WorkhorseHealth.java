package com.dataweave.api.application.bridge;

/**
 * workhorse 大脑可用性视图（供 AG-UI 编排与诊断 SPI 决定走真实大脑还是降级 mock）。
 *
 * <p>生产实现 {@link WorkhorseHealthProbe} 周期探测 sidecar 健康；测试可注入固定实现。
 */
public interface WorkhorseHealth {

    /** workhorse 当前是否可用（已配置 workhorse 模式且最近一次探测健康）。 */
    boolean isHealthy();
}
