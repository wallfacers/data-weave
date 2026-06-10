package com.dataweave.api.application;

import java.util.Map;

/**
 * 意图路由产物：Markdown 文本回复 + 可选结构化结果 + 可选 CUSTOM 事件名。
 *
 * @param markdown         完整 Markdown 文本（编排器会按句切成多个 delta 流式输出）
 * @param structured       可选结构化结果，作为 AG-UI CUSTOM 事件的 value；
 *                         至少含 kind(table/metric/lineage/task/fleet/diagnosis)，以及 columns/rows 等
 * @param customEventName  可选 CUSTOM 事件名；为空时默认 {@code dataweave.result}。
 *                         诊断结果用 {@code dataweave.diagnosis}。
 */
public record AgentReply(String markdown, Map<String, Object> structured, String customEventName) {

    /** 兼容旧调用：默认 CUSTOM 事件名 dataweave.result。 */
    public AgentReply(String markdown, Map<String, Object> structured) {
        this(markdown, structured, null);
    }

    public static AgentReply text(String markdown) {
        return new AgentReply(markdown, null, null);
    }
}
