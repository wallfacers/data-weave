package com.dataweave.api.application.mcp;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 一个 MCP 工具定义。handler 接收参数 map + agent locale，返回结果负载（String 或可序列化对象）。
 *
 * <p>locale 由 MCP 端点从 {@code x-dw-agent-locale} 请求头解析（缺失回退 {@link Locale#SIMPLIFIED_CHINESE}），
 * 写工具经闸门时需要它本地化裁决理由与闸门响应。
 *
 * @param name        工具名（tools/list 暴露）
 * @param description 描述（agent 能力发现）
 * @param inputSchema JSON Schema 形参定义
 * @param handler     执行逻辑（复用 master 领域服务 / 经 PolicyEngine 闸门）
 */
public record McpTool(String name,
                      String description,
                      Map<String, Object> inputSchema,
                      Handler handler) {

    /** Handler 上下文：工具参数 + agent locale + 租户/用户身份（E1 MCP 身份注入）。 */
    public record Context(Map<String, Object> args, Locale locale, Long tenantId, Long userId) {
        public Object arg(String key) {
            return args == null ? null : args.get(key);
        }
    }

    /** 工具执行函数。 */
    @FunctionalInterface
    public interface Handler {
        Object apply(Context ctx);
    }
}
