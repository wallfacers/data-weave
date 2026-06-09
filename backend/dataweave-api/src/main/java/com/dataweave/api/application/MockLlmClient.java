package com.dataweave.api.application;

import org.springframework.stereotype.Component;

/**
 * 规则化 mock LLM 实现。MVP 不调真实模型，返回固定的占位补全。
 *
 * <p>真模型替换点：后期新增一个 @Primary 的真实实现（如基于 webClientBuilder 调用 OpenAI 兼容端点），
 * 或换为 LangChain4j ChatModel 适配，本接口与 IntentRouter 编排不变。
 */
@Component
public class MockLlmClient implements LlmClient {

    @Override
    public String complete(String prompt) {
        // mock: 不做真实推理，仅回显。意图识别与回复生成由规则路由器负责，不依赖此返回。
        return "[mock-llm] " + prompt;
    }
}
