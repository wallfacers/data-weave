package com.dataweave.api.application;

import java.util.Map;

/**
 * 意图路由产物：Markdown 文本回复 + 可选结构化结果 + 可选 CUSTOM 事件名 + 可选视图召唤。
 *
 * @param markdown         完整 Markdown 文本（编排器会按句切成多个 delta 流式输出）
 * @param structured       可选结构化结果，作为 AG-UI CUSTOM 事件的 value；
 *                         至少含 kind(table/metric/lineage/task/fleet/diagnosis)，以及 columns/rows 等
 * @param customEventName  可选 CUSTOM 事件名；为空时默认 {@code dataweave.result}。
 *                         诊断结果用 {@code dataweave.diagnosis}。
 * @param uiOpen           可选视图召唤：编排器据此补发 {@code CUSTOM(dataweave.ui.open)}，
 *                         前端 Workspace 打开/激活对应 tab。
 */
public record AgentReply(String markdown, Map<String, Object> structured,
                         String customEventName, UiOpen uiOpen) {

    /** AI 召唤前端视图：{@code dataweave.ui.open} 事件载荷（view + 可选 params）。 */
    public record UiOpen(String view, Map<String, Object> params) {
    }

    /** 兼容旧调用：无视图召唤。 */
    public AgentReply(String markdown, Map<String, Object> structured, String customEventName) {
        this(markdown, structured, customEventName, null);
    }

    /** 兼容旧调用：默认 CUSTOM 事件名 dataweave.result，无视图召唤。 */
    public AgentReply(String markdown, Map<String, Object> structured) {
        this(markdown, structured, null, null);
    }

    public static AgentReply text(String markdown) {
        return new AgentReply(markdown, null, null, null);
    }

    /** 附加视图召唤（无参数）。 */
    public AgentReply opening(String view) {
        return opening(view, null);
    }

    /** 附加视图召唤（带参数）。 */
    public AgentReply opening(String view, Map<String, Object> params) {
        return new AgentReply(markdown, structured, customEventName, new UiOpen(view, params));
    }
}
