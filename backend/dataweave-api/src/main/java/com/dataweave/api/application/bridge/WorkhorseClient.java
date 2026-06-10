package com.dataweave.api.application.bridge;

import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * workhorse-agent 会话客户端。生产实现走 REST/SSE（{@link WorkhorseHttpClient}）；
 * 集成测试用 stub 提供脚本化事件流（6.4）。
 */
public interface WorkhorseClient {

    /** 建会话，带 instructions/metadata，返回 workhorse 会话 id。 */
    String createSession(String instructions, Map<String, Object> metadata);

    /** 向会话发消息，流式返回 workhorse 事件。 */
    Flux<WorkhorseEvent> sendMessage(String sessionId, String content);
}
