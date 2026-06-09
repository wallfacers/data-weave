package com.dataweave.api.application;

/**
 * LLM 客户端抽象。MVP 默认 mock 实现；后期接 LangChain4j / WebClient 直调真实模型时，
 * 仅替换实现（{@link MockLlmClient}），不动编排骨架（{@link IntentRouter} / 编排器）。
 */
public interface LlmClient {

    /**
     * 给定 prompt 返回补全文本。
     */
    String complete(String prompt);
}
