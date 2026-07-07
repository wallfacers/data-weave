package com.dataweave.master.domain.lineage;

/**
 * 血缘边来源：AGENT 声明 / SQL_PARSED 解析 / FORM 表单；
 * 041 脚本通道：SCRIPT_SQL 内嵌 SQL 解析（确定）/ SCRIPT_INFERRED 规则推断 / SCRIPT_MODEL 模型推断；
 * 053 云 AI 通道：SCRIPT_AGENT 可配置云大模型异步推断（Anthropic/OpenAI 协议，由 LineageAgentEnricher 异步并入）。
 */
public enum Source {
    AGENT,
    SQL_PARSED,
    FORM,
    SCRIPT_SQL,
    SCRIPT_INFERRED,
    SCRIPT_MODEL,
    SCRIPT_AGENT
}
