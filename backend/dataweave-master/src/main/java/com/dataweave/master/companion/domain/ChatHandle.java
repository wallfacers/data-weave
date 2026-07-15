package com.dataweave.master.companion.domain;

/**
 * 已开启的管家会话句柄。{@link #send} 阻塞至本轮结束（期间经 {@link ChatCallbacks} 回调增量/结束）；
 * {@link #cancel} 可从另一线程打断进行中的 send（L0 免审批，对齐 070 打断先例）。
 *
 * <p>{@link #sessionId} 对应 workhorse session id，落 {@code companion_message.brain_session_id} 供续聊/排障。
 */
public interface ChatHandle {

    /** workhorse session id（落库 brain_session_id）。 */
    String sessionId();

    /**
     * 发送用户消息并阻塞至本轮结束（期间回调 delta/end）。返回管家本轮完整回复文本。
     */
    String send(String userText);

    /** 打断当前进行中的 send（向 brain 发 cancel，send 随即以 interrupted 结束）。 */
    void cancel();
}
