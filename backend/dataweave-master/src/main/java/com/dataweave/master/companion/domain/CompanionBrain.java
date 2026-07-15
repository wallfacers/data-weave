package com.dataweave.master.companion.domain;

/**
 * 管家对话/巡检大脑端口（领域接口，非 HTTP）。推理全部在 workhorse sidecar 进程，
 * master 只做编排/通道/治理——满足 constitution IV"服务端无 AI 大脑"。
 *
 * <p>两个实现：{@code WorkhorseBrainClient}（HTTP sidecar，生产）与 {@code MockBrain}（降级/测试）。
 * 选择策略见 {@code CompanionBrainSelector}：巡检容忍降级（→未完成汇报），对话要求 workhorse 在线
 * （否则 {@code companion.brain_unavailable}）。
 *
 * @see com.dataweave.master.companion.domain.ChatHandle
 * @see com.dataweave.master.companion.domain.PatrolResult
 */
public interface CompanionBrain {

    /**
     * 开启/续接一个会话（{@code contextPrompt} 注入巡检上下文/人设指令）。
     * 返回的句柄支持 {@link ChatHandle#send} 触发流式（经 {@code callbacks} 回调）与 {@link ChatHandle#cancel} 打断。
     */
    ChatHandle openChat(long projectId, String contextPrompt, ChatCallbacks callbacks);

    /**
     * headless 单轮巡检：按领域模板提示 sidecar，消费到 turn 结束，返回结构化产出。
     * 解析失败/超时/brain 不可用 → 返回 {@link PatrolResult#failed}（由调用方产 INFO"未完成"汇报，SC-007 零静默丢失）。
     */
    PatrolResult runPatrol(PatrolRoutine routine, String scopeJson, int timeoutSeconds);

    /** 大脑是否可用（健康探测）。 */
    boolean healthy();

    /** 实现名（workhorse / mock，日志与选择用）。 */
    String name();
}
