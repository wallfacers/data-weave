package com.dataweave.master.application.lineage.agent;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.dataweave.master.domain.lineage.LineageAgentConfig;

/**
 * 053 双协议适配归一接口（契约 llm-protocol-adapters C1，FR-002）。两实现把各自响应归一为 {@link AgentExtraction}，
 * 协议差异（端点/鉴权头/请求结构/结构化输出方式）对上层不可见。
 */
public interface LlmProtocolAdapter {

    String protocol();  // "ANTHROPIC" | "OPENAI"

    /** 构造外呼请求（鉴权头按协议注入）。apiKeyPlain 来自 DatasourceEncryptor.decrypt，即用即弃（FR-020）。 */
    HttpRequest buildRequest(LineageAgentConfig cfg, LineageExtractionPrompt.LineagePrompt prompt, String apiKeyPlain);

    /** 解析响应体归一为 AgentExtraction；解析失败抛 unchecked → 上层降级为空产物并留痕（FR-006）。 */
    AgentExtraction parseResponse(String body, String modelVersion);

    // ===== 069：通用多轮对话通道（诊断/对话/修复提案/战况播报复用），与血缘抽取通道并列 =====

    /** 构造通用对话请求（systemPrompt + 多轮 messages，无 emit_lineage 工具约束）；stream=true 时开启 SSE 增量输出。 */
    HttpRequest buildChatRequest(LineageAgentConfig cfg, String systemPrompt,
                                  List<LlmChatClient.ChatMessage> messages, String apiKeyPlain, boolean stream);

    /** 非流式：从完整响应体中抽取全文文本。 */
    String parseChatText(String body);

    /**
     * 流式：从一条 SSE {@code data:} 行（已去除 "data: " 前缀）中抽取增量文本片段；
     * 非文本增量事件（心跳/元数据/结束标记）返回 null。
     */
    String parseChatDelta(String sseDataLine);

    // ===== 共享：emit_lineage 工具/响应的 JSON schema + 归一 helper =====

    /** 两协议共用的输出 schema（reads/writes/columnEdges/confidence）。 */
    @SuppressWarnings("unchecked")
    Map<String, Object> EMISSION_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "reads", Map.of("type", "array", "items", Map.of("type", "string")),
                    "writes", Map.of("type", "array", "items", Map.of("type", "string")),
                    "columnEdges", Map.of("type", "array", "items", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "srcTable", Map.of("type", "string"),
                                    "srcColumn", Map.of("type", "string"),
                                    "dstTable", Map.of("type", "string"),
                                    "dstColumn", Map.of("type", "string")))),
                    "confidence", Map.of("type", "number")),
            "required", List.of("reads", "writes"));

    /** 从归一 Map 抽取 AgentExtraction（两协议解析到 input/content 后共用）。 */
    @SuppressWarnings("unchecked")
    static AgentExtraction mapExtraction(Map<String, Object> input, String modelVersion) {
        if (input == null) return AgentExtraction.empty(modelVersion);
        List<String> reads = asStringList(input.get("reads"));
        List<String> writes = asStringList(input.get("writes"));
        List<AgentExtraction.ColumnEdge> edges = new ArrayList<>();
        Object edgesRaw = input.get("columnEdges");
        if (edgesRaw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    edges.add(new AgentExtraction.ColumnEdge(
                            asStr(m.get("srcTable")), asStr(m.get("srcColumn")),
                            asStr(m.get("dstTable")), asStr(m.get("dstColumn"))));
                }
            }
        }
        return new AgentExtraction(reads, writes, edges, asDouble(input.get("confidence")), modelVersion);
    }

    static List<String> asStringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) out.add(asStr(o));
        }
        return out;
    }

    static String asStr(Object o) {
        return o == null ? "" : o.toString();
    }

    static double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
