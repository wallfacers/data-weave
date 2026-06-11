package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;

/**
 * 平台侧动作执行器：按已落库的 {@link AgentAction}（票据）执行真实动作。
 *
 * <p>L0/L1 直接执行与 L2/L3 批准后执行走同一入口——执行不经 LLM，参数取自票据原值（design D4）。
 */
public interface PlatformActionExecutor {

    ExecOutcome execute(AgentAction action);

    /**
     * 执行结果。
     *
     * @param success          是否成功
     * @param message          面向用户的反馈
     * @param resultJson       结构化结果（落 agent_action.result_json）
     * @param resultInstanceId 若产生重跑实例，其 id
     */
    record ExecOutcome(boolean success, String message, String resultJson, java.util.UUID resultInstanceId) {
    }
}
