package com.dataweave.master.companion.domain;

/**
 * 对话流式回调。delta 增量是瞬态（只走 SSE 不落库），语义完整后由调用方落一条 AGENT 消息。
 *
 * <p>对应契约 SSE {@code delta}/{@code end} 事件：{@link #onDelta} 推增量，{@link #onEnd} 标记本轮结束
 * （{@code interrupted=true} 表示被用户打断，输出半截文本）。
 */
public interface ChatCallbacks {

    /** 管家流式增量片段。 */
    void onDelta(String chunk);

    /** 本轮流式结束（完整文本或被打断的半截）。 */
    void onEnd(String fullText, boolean interrupted);

    /** 流式异常（brain 报错/连接中断）。默认实现把异常透传给 onEnd 的半截文本。 */
    default void onError(Throwable error) {
        onEnd(error.getMessage() == null ? "" : error.getMessage(), true);
    }
}
