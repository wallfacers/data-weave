package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;

import java.util.Locale;

/**
 * SPI：平台动作处理器——供业务模块（alert 等）注册，避免 master 反向依赖。
 *
 * <p>master 的 {@link DefaultPlatformActionExecutor} 在 switch 未命中时兜底遍历注入的
 * {@code List<PlatformActionHandler>}，找到第一个 {@code supports(actionType)} 的委派执行。
 * 这样 master 编译期只见本接口，不依赖任何业务模块。
 */
public interface PlatformActionHandler {

    /** 该 handler 是否支持处理此 actionType */
    boolean supports(String actionType);

    /** 执行动作，返回结果 */
    PlatformActionExecutor.ExecOutcome handle(AgentAction action, Locale locale);
}
