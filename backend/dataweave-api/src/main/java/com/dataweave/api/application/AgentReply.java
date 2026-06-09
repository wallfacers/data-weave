package com.dataweave.api.application;

import java.util.Map;

/**
 * 意图路由产物：Markdown 文本回复 + 可选结构化结果。
 *
 * @param markdown    完整 Markdown 文本（编排器会按句切成多个 delta 流式输出）
 * @param structured  可选结构化结果，作为 AG-UI CUSTOM 事件（name=dataweave.result）的 value；
 *                    至少含 kind(table/metric/lineage/task)，以及 columns/rows 等
 */
public record AgentReply(String markdown, Map<String, Object> structured) {

    public static AgentReply text(String markdown) {
        return new AgentReply(markdown, null);
    }
}
