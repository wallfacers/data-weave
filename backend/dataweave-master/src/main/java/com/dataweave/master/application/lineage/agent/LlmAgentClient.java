package com.dataweave.master.application.lineage.agent;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.dataweave.master.application.DatasourceEncryptor;
import com.dataweave.master.domain.lineage.LineageAgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static java.util.stream.Collectors.toMap;

/**
 * 053 云 AI 外呼客户端（契约 llm-protocol-adapters C1/C5，FR-002/FR-020/FR-022）。
 * 按 {@link LineageAgentConfig#protocol()} 分发到对应 {@link LlmProtocolAdapter}；
 * 用 JDK {@link HttpClient}（与 ModelExtractor 同选型，master 零新增依赖），超时 = cfg.timeoutMs。
 * apiKeyPlain 来自 {@link DatasourceEncryptor#decrypt} 即用即弃，绝不进日志（FR-020）。
 */
@Component
public class LlmAgentClient {

    private static final Logger log = LoggerFactory.getLogger(LlmAgentClient.class);

    private final Map<String, LlmProtocolAdapter> adapters;
    private final DatasourceEncryptor encryptor;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public LlmAgentClient(List<LlmProtocolAdapter> adapterList, DatasourceEncryptor encryptor) {
        this.adapters = adapterList.stream().collect(toMap(LlmProtocolAdapter::protocol, a -> a));
        this.encryptor = encryptor;
    }

    /** 一次外呼的归一结果（含耗时与可能的错因）；永不抛，错因进 error 供上层降级/留痕。 */
    public record CallResult(AgentExtraction extraction, long latencyMs, String error) {}

    /** 抽取入口（AgentLineageExtractor 调）；error!=null 时调用方按降级处理。 */
    public CallResult extract(LineageAgentConfig cfg, String scriptContent, String taskType) {
        return doExtract(cfg, scriptContent, taskType, Collections.emptyMap());
    }

    /**
     * 含 schema 接地的抽取入口（US3/FR-016/T028-T029）。
     * @param tableColumns 表名 → 该表真实列名清单，注入提示作接地上下文；为空时退化为无 schema 标准提示
     */
    public CallResult extract(LineageAgentConfig cfg, String scriptContent, String taskType,
                              Map<String, List<String>> tableColumns) {
        return doExtract(cfg, scriptContent, taskType, tableColumns);
    }

    /** 探活入口（Controller /test 调）；发一次最小外呼验证连通+鉴权+解析，永不抛。 */
    public CallResult test(LineageAgentConfig cfg) {
        return doExtract(cfg, "SELECT 1", "SQL", Collections.emptyMap());
    }

    private CallResult doExtract(LineageAgentConfig cfg, String scriptContent, String taskType) {
        return doExtract(cfg, scriptContent, taskType, Collections.emptyMap());
    }

    private CallResult doExtract(LineageAgentConfig cfg, String scriptContent, String taskType,
                                  Map<String, List<String>> tableColumns) {
        long t0 = System.nanoTime();
        LlmProtocolAdapter adapter = adapters.get(cfg.protocol());
        if (adapter == null) {
            return new CallResult(AgentExtraction.empty(cfg.model()), 0, "no adapter for protocol: " + cfg.protocol());
        }
        try {
            String apiKeyPlain = decryptKey(cfg);  // 即用即弃，不进日志
            var prompt = (tableColumns != null && !tableColumns.isEmpty())
                    ? LineageExtractionPrompt.buildWithSchema(scriptContent, taskType, tableColumns)
                    : LineageExtractionPrompt.build(scriptContent, taskType);
            HttpRequest req = adapter.buildRequest(cfg, prompt, apiKeyPlain);
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - t0) / 1_000_000;
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("[LlmAgent] {} non-2xx ({}ms): status={}", cfg.protocol(), latency, resp.statusCode());
                return new CallResult(AgentExtraction.empty(cfg.model()), latency, "http " + resp.statusCode());
            }
            AgentExtraction ex = adapter.parseResponse(resp.body(), cfg.model());
            return new CallResult(ex, latency, null);
        } catch (Exception e) {
            long latency = (System.nanoTime() - t0) / 1_000_000;
            // 异常信息不得含明文 key（adapter/client 均不把 key 放 message）
            log.warn("[LlmAgent] {} call degraded ({}ms): {}", cfg.protocol(), latency, e.toString());
            return new CallResult(AgentExtraction.empty(cfg.model()), latency, e.toString());
        }
    }

    private String decryptKey(LineageAgentConfig cfg) {
        if (cfg.apiKeyEnc() == null || cfg.apiKeyEnc().isEmpty()) return null;
        try {
            return encryptor.decrypt(cfg.apiKeyEnc());
        } catch (Exception e) {
            log.warn("[LlmAgent] apiKey decrypt failed (treated as no key): {}", e.toString());
            return null;
        }
    }
}
