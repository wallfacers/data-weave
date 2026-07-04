package com.dataweave.worker.interfaces;

import com.dataweave.master.domain.lineage.StatementMetric;
import com.dataweave.worker.WorkerApplication;
import com.dataweave.worker.application.IncarnationManager;
import com.dataweave.worker.application.WorkerExecService;
import com.dataweave.worker.domain.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Worker exec HTTP 端点（task 3.2 / 3.7）：接收 master 下发的任务执行请求。
 *
 * <p>端点 {@code POST /internal/worker/exec}，共享 token 鉴权。
 * 幂等：{@link WorkerExecService} 按 (instance_id, attempt) 去重。
 * 执行完成后通过 HTTP 回调 master 的 /api/cluster/report。
 */
@RestController
@RequestMapping("/internal/worker")
public class WorkerExecController {

    private static final Logger log = LoggerFactory.getLogger(WorkerExecController.class);

    private final WorkerExecService execService;
    private final WorkerApplication workerApp;
    private final String clusterToken;
    private final String masterUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WorkerExecController(WorkerExecService execService,
                                WorkerApplication workerApp,
                                @Value("${cluster.auth.token:}") String clusterToken,
                                @Value("${dataweave.master.url:http://localhost:8000}") String masterUrl) {
        this.execService = execService;
        this.workerApp = workerApp;
        this.clusterToken = clusterToken;
        this.masterUrl = masterUrl;
    }

    @PostMapping("/exec")
    public ResponseEntity<Map<String, Object>> exec(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, Object> body) {
        // 鉴权
        if (clusterToken != null && !clusterToken.isBlank()) {
            String expected = "Bearer " + clusterToken;
            if (!expected.equals(auth)) {
                return ResponseEntity.ok(Map.of("accepted", false, "reason", "auth_failed"));
            }
        }

        // 优雅停机中拒新任务
        if (!workerApp.isAccepting()) {
            return ResponseEntity.ok(Map.of("accepted", false, "reason", "draining"));
        }

        UUID instanceId;
        try {
            instanceId = UUID.fromString((String) body.get("taskInstanceId"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("accepted", false, "reason", "invalid_instance_id"));
        }

        int attempt = body.get("attempt") instanceof Number n ? n.intValue() : 1;
        String bizDate = (String) body.getOrDefault("bizDate", "");
        String content = (String) body.getOrDefault("content", "");
        int timeoutSeconds = body.get("timeoutSeconds") instanceof Number n ? n.intValue() : 0;
        String taskType = (String) body.getOrDefault("taskType", "SHELL");

        // C4.2：反序列化 over-wire 数据源 → 完整 ExecutionContext（worker 不新增 DB 依赖，与 all-in-one 对称）
        ExecutionContext ctx = buildContextFromBody(body, content, bizDate, attempt, timeoutSeconds, taskType);

        boolean accepted = execService.submit(instanceId, attempt, ctx, null,
                new ReportCallback(instanceId), parseLocale(body));

        return ResponseEntity.ok(Map.of("accepted", accepted,
                "reason", accepted ? "executing" : "duplicate"));
    }

    /** 反序列化 exec body 的 datasource 字段 → 构建 ExecutionContext（SQL DataSourceRef / SHELL env / PYTHON 落盘 / SPARK）。 */
    @SuppressWarnings("unchecked")
    static ExecutionContext buildContextFromBody(Map<String, Object> body, String content, String bizDate,
                                                   int attempt, int timeoutSeconds, String taskType) {
        // SPARK 内容形态来自 body 顶层（任务属性，独立于数据源）
        String sparkMode = (String) body.get("sparkMode");
        String jarRef = (String) body.get("jarRef");
        String mainClass = (String) body.get("mainClass");

        Object dsObj = body.get("datasource");
        if (!(dsObj instanceof Map)) {
            // 无数据源：SPARK 任务仍须带 sparkMode（sparkHome/master 缺 → 执行器判 SKIPPED，不丢形态）
            ExecutionContext.SparkSubmitRef sparkNoDs = "SPARK".equals(taskType)
                    ? new ExecutionContext.SparkSubmitRef(null, null, null, null, null, sparkMode, jarRef, mainClass)
                    : null;
            return new ExecutionContext(content, bizDate, attempt, timeoutSeconds, null, taskType,
                    null, null, null, sparkNoDs);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> dsInfo = (Map<String, Object>) dsObj;
        String dsType = (String) dsInfo.getOrDefault("taskType", taskType);
        ExecutionContext.DataSourceRef dsRef = null;
        Map<String, String> shellEnvVars = null;
        String pythonConfigPath = null;
        ExecutionContext.SparkSubmitRef spark = null;
        switch (dsType) {
            case "SQL" -> dsRef = new ExecutionContext.DataSourceRef(
                    (String) dsInfo.get("name"), (String) dsInfo.get("typeCode"), (String) dsInfo.get("jdbcUrl"),
                    (String) dsInfo.get("username"), (String) dsInfo.get("password"),
                    dsInfo.get("driverJarId") instanceof Number num ? num.longValue() : null,
                    (String) dsInfo.get("driverClass"), (String) dsInfo.get("storageKey"));
            case "SHELL" -> shellEnvVars = toStringMap(dsInfo.get("shellEnvVars"));
            case "PYTHON" -> {
                Object cfg = dsInfo.get("pythonConfigJson");
                if (cfg instanceof String json && !json.isEmpty()) {
                    pythonConfigPath = writeWorkerPythonConfig(json);
                }
            }
            case "SPARK" -> spark = new ExecutionContext.SparkSubmitRef(
                    (String) dsInfo.get("sparkHome"), (String) dsInfo.get("master"),
                    (String) dsInfo.get("deployMode"), (String) dsInfo.get("queue"),
                    toStringMap(dsInfo.get("conf")), sparkMode, jarRef, mainClass);
            default -> { /* 未知 dsType：留空，执行器侧判 SKIPPED/失败 */ }
        }
        return new ExecutionContext(content, bizDate, attempt, timeoutSeconds, null, taskType,
                dsRef, shellEnvVars, pythonConfigPath, spark);
    }

    /** PYTHON over-wire：把 master 序列化的配置 JSON 落盘为 worker 本地 DW_DATASOURCE_CONFIG 文件（600 权限）。 */
    private static String writeWorkerPythonConfig(String json) {
        try {
            Path tmp = Files.createTempFile("dw-ds-py-", ".json");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX（Windows）— skip
            }
            return tmp.toString();
        } catch (IOException e) {
            log.warn("[WorkerExec] 写 PYTHON 数据源临时文件失败：{}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> toStringMap(Object o) {
        if (!(o instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return out;
    }

    /**
     * 状态回报回调：通过 HTTP 回调 master 的 /api/cluster/report。
     */
    private class ReportCallback implements WorkerExecService.ReportCallback {
        private final UUID taskInstanceId;

        ReportCallback(UUID taskInstanceId) {
            this.taskInstanceId = taskInstanceId;
        }

        @Override
        public void onStarted(UUID id) {
            reportToMaster("started", id, null, null, null, null);
        }

        @Override
        public void onFinished(UUID id, int exitCode, String tailLog, List<StatementMetric> statementMetrics) {
            reportToMaster("finished", id, exitCode, tailLog, null, statementMetrics);
        }

        @Override
        public void onFailed(UUID id, String reason, String tailLog) {
            reportToMaster("failed", id, null, tailLog, reason, null);
        }

        private void reportToMaster(String event, UUID id, Integer exitCode, String tailLog,
                                    String failureReason, List<StatementMetric> statementMetrics) {
            try {
                // Jackson 序列化整 payload（feature 025：正确转义 SQL 文本 "/换行/反斜杠 + statementMetrics 数组）
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("event", event);
                payload.put("taskInstanceId", String.valueOf(id));
                if (exitCode != null) {
                    payload.put("exitCode", exitCode);
                }
                if (tailLog != null) {
                    payload.put("tailLog", tailLog);
                }
                if (failureReason != null) {
                    payload.put("failureReason", failureReason);
                }
                if (statementMetrics != null && !statementMetrics.isEmpty()) {
                    payload.put("statementMetrics", statementMetrics);
                }
                String json = objectMapper.writeValueAsString(payload);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(masterUrl + "/api/cluster/report"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + clusterToken)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                log.warn("[WorkerExec] 回报 master 失败：instance={}, event={}, error={}",
                        id, event, e.getMessage());
            }
        }
    }

    /** 解析 body 中的 locale 字段；null/空/无效 → null（WorkerExecService 兜底 zh-CN）。 */
    private static Locale parseLocale(Map<String, Object> body) {
        Object raw = body.get("locale");
        if (!(raw instanceof String tag) || tag.isBlank()) {
            return null;
        }
        try {
            return Locale.forLanguageTag(tag);
        } catch (Exception ignored) {
            return null;
        }
    }
}
