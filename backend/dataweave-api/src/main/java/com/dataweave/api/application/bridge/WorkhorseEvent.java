package com.dataweave.api.application.bridge;

/**
 * workhorse 会话 SSE 事件（provisional 契约，待 support-dataweave-headless-integration design 对齐）。
 *
 * <p>type：
 * <ul>
 *   <li>{@code text} —— 模型文本增量（text）</li>
 *   <li>{@code tool_call_start} —— 工具调用开始（toolUseId/toolName/inputJson）</li>
 *   <li>{@code tool_call_done} —— 工具调用完成（toolUseId/output/truncated）</li>
 *   <li>{@code permission_resolved} —— 权限决议（toolUseId/decision/source）</li>
 *   <li>{@code done} —— 运行结束</li>
 * </ul>
 */
public record WorkhorseEvent(String type,
                             String text,
                             String toolUseId,
                             String toolName,
                             String inputJson,
                             String output,
                             boolean truncated,
                             String decision,
                             String source) {

    public static WorkhorseEvent text(String text) {
        return new WorkhorseEvent("text", text, null, null, null, null, false, null, null);
    }

    public static WorkhorseEvent toolStart(String toolUseId, String toolName, String inputJson) {
        return new WorkhorseEvent("tool_call_start", null, toolUseId, toolName, inputJson, null, false, null, null);
    }

    public static WorkhorseEvent toolDone(String toolUseId, String output, boolean truncated) {
        return new WorkhorseEvent("tool_call_done", null, toolUseId, null, null, output, truncated, null, null);
    }

    public static WorkhorseEvent permission(String toolUseId, String decision, String source) {
        return new WorkhorseEvent("permission_resolved", null, toolUseId, null, null, null, false, decision, source);
    }

    public static WorkhorseEvent done() {
        return new WorkhorseEvent("done", null, null, null, null, null, false, null, null);
    }
}
