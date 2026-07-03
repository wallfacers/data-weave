package com.dataweave.master.application.lineage.script;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.dataweave.master.domain.lineage.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型推断抽取器（041 US4，SCRIPT_MODEL 通道，research D12）。
 *
 * <p>调独立推理 sidecar（{@code POST /extract}，contracts §5）；宪法原则 IV 合规姿态：
 * 推理不嵌入平台进程，本类只是 HTTP 客户端。{@code lineage.model.endpoint} 未配置或探活失败
 * → {@link #supports} false 整体旁路；超时/异常 → 空产物降级（FR-013 零阻断）。
 *
 * <p>防幻觉双重校验（FR-012）：输出结构合法 + 表名可在脚本文本中字面定位；拒收留痕。
 * 实现选型：JDK HttpClient 而非 WebClient——master 模块零新增依赖，且 push 内同步调用
 * 阻塞语义与 2s 预算天然匹配。
 */
@Component
public class ModelExtractor implements ScriptLineageExtractor {

    private static final Logger log = LoggerFactory.getLogger(ModelExtractor.class);
    private static final Pattern TABLE_NAME =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$");
    private static final long HEALTH_TTL_NANOS = 30_000_000_000L;   // 30s 探活缓存

    private final String endpoint;
    private final long timeoutMs;
    private final HttpClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile long lastHealthCheck;
    private volatile boolean healthy;

    public ModelExtractor(@Value("${lineage.model.endpoint:}") String endpoint,
                          @Value("${lineage.model.timeout-ms:2000}") long timeoutMs) {
        this.endpoint = endpoint == null ? "" : endpoint.strip();
        this.timeoutMs = timeoutMs;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, timeoutMs / 2)))
                .build();
    }

    @Override
    public boolean supports(String taskType) {
        if (endpoint.isBlank() || taskType == null) {
            return false;   // 未部署 sidecar → 整体旁路（FR-013）
        }
        String t = taskType.toUpperCase(Locale.ROOT);
        if (!"PYTHON".equals(t) && !"SHELL".equals(t) && !"SPARK".equals(t)) {
            return false;
        }
        return healthCheck();
    }

    @Override
    public ScriptExtraction extract(ScriptSource source) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "taskType", source.taskType(), "content", source.content()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/extract"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[ModelExtractor] sidecar {} (degrade)", response.statusCode());
                return ScriptExtraction.empty(Source.SCRIPT_MODEL);
            }
            return validate(source, objectMapper.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            healthy = false;   // 失败即打断健康缓存，下次 supports 重探
            log.warn("[ModelExtractor] extract degraded (FR-013): {}", e.toString());
            return ScriptExtraction.empty(Source.SCRIPT_MODEL);
        }
    }

    /** 双重校验：结构 + 表名脚本内可定位（FR-012 防幻觉）；拒收留痕。 */
    @SuppressWarnings("unchecked")
    private ScriptExtraction validate(ScriptSource source, Map<String, Object> payload) {
        String modelVersion = String.valueOf(payload.getOrDefault("modelVersion", "unknown"));
        String contentLower = source.content().toLowerCase(Locale.ROOT);
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        List<ScriptExtraction.Hint> hints = new ArrayList<>();
        for (var entry : List.of(
                Map.entry("reads", reads), Map.entry("writes", writes))) {
            Object list = payload.get(entry.getKey());
            if (!(list instanceof List<?> items)) {
                continue;
            }
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> m) || !(m.get("table") instanceof String table)) {
                    continue;
                }
                String t = table.strip();
                if (TABLE_NAME.matcher(t).matches()
                        && contentLower.contains(t.toLowerCase(Locale.ROOT))) {
                    entry.getValue().add(t);
                } else {
                    hints.add(new ScriptExtraction.Hint(ScriptExtraction.HintKind.PARSE_FAIL, 0,
                            "model output rejected (not locatable): " + abbreviate(t)));
                }
            }
        }
        return new ScriptExtraction(reads, writes, List.of(), hints, Source.SCRIPT_MODEL, modelVersion);
    }

    private boolean healthCheck() {
        long now = System.nanoTime();
        if (now - lastHealthCheck < HEALTH_TTL_NANOS) {
            return healthy;
        }
        lastHealthCheck = now;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/health"))
                    .timeout(Duration.ofMillis(Math.max(500, timeoutMs / 2)))
                    .GET().build();
            healthy = client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            healthy = false;
        }
        if (!healthy) {
            log.warn("[ModelExtractor] sidecar unhealthy, bypassing model channel: {}", endpoint);
        }
        return healthy;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : (s.length() > 120 ? s.substring(0, 120) : s);
    }
}
