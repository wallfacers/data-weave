package com.dataweave.api.application.mcp;

/**
 * 工具调用结果（已截断、含完整输出引用）。
 *
 * @param text      返回给 agent 的文本（可能截断，带 [truncated] 标记）
 * @param isError   是否为错误结果
 * @param truncated 是否被截断
 * @param outputRef 完整输出引用（截断时为存档文件路径，否则 null）
 */
public record ToolResult(String text, boolean isError, boolean truncated, String outputRef) {

    static ToolResult ok(String text) {
        return new ToolResult(text, false, false, null);
    }

    static ToolResult error(String text) {
        return new ToolResult(text, true, false, null);
    }
}
