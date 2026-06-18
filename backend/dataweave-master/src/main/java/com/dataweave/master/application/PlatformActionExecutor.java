package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;

import java.util.Locale;

/**
 * 平台侧动作执行器：按已落库的 {@link AgentAction}（票据）执行真实动作。
 *
 * <p>L0/L1 直接执行与 L2/L3 批准后执行走同一入口——执行不经 LLM，参数取自票据原值（design D4）。
 *
 * <p>{@link #execute(AgentAction, Locale)} 接收 locale，实现方按此本地化 {@code ExecOutcome.message}
 * （用户在 UI 看到的反馈）。
 */
public interface PlatformActionExecutor {

    /** 默认 locale（中文）执行。 */
    default ExecOutcome execute(AgentAction action) {
        return execute(action, Locale.SIMPLIFIED_CHINESE);
    }

    /** 按 locale 本地化执行结果 message。 */
    ExecOutcome execute(AgentAction action, Locale locale);

    /**
     * 执行结果。
     *
     * @param success          是否成功
     * @param message          面向用户的反馈（按 locale 本地化）
     * @param resultJson       结构化结果（落 agent_action.result_json）
     * @param resultInstanceId 若产生重跑实例，其 id
     */
    record ExecOutcome(boolean success, String message, String resultJson, java.util.UUID resultInstanceId) {
    }
}
