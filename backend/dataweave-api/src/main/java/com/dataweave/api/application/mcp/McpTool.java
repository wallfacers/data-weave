package com.dataweave.api.application.mcp;

import java.util.Map;
import java.util.function.Function;

/**
 * 一个 MCP 工具定义。handler 接收参数 map，返回结果负载（String 或可序列化对象）。
 *
 * @param name        工具名（tools/list 暴露）
 * @param description 描述（agent 能力发现）
 * @param inputSchema JSON Schema 形参定义
 * @param handler     执行逻辑（复用 master 领域服务 / 经 PolicyEngine 闸门）
 */
public record McpTool(String name,
                      String description,
                      Map<String, Object> inputSchema,
                      Function<Map<String, Object>, Object> handler) {
}
