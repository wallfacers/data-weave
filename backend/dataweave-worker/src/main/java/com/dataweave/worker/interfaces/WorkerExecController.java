package com.dataweave.worker.interfaces;

import com.dataweave.worker.WorkerApplication;
import com.dataweave.worker.application.IncarnationManager;
import com.dataweave.worker.application.WorkerExecService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
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
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WorkerExecController(WorkerExecService execService,
                                WorkerApplication workerApp,
                                @Value("${cluster.auth.token:}") String clusterToken,
                                @Value("${dataweave.master.url:http://localhost:8080}") String masterUrl) {
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

        boolean accepted = execService.submit(instanceId, attempt, bizDate, content, timeoutSeconds,
                null, new ReportCallback(instanceId));

        return ResponseEntity.ok(Map.of("accepted", accepted,
                "reason", accepted ? "executing" : "duplicate"));
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
            reportToMaster("started", id, null, null, null);
        }

        @Override
        public void onFinished(UUID id, int exitCode, String tailLog) {
            reportToMaster("finished", id, exitCode, tailLog, null);
        }

        @Override
        public void onFailed(UUID id, String reason, String tailLog) {
            reportToMaster("failed", id, null, tailLog, reason);
        }

        private void reportToMaster(String event, UUID id, Integer exitCode, String tailLog, String failureReason) {
            try {
                StringBuilder json = new StringBuilder("{\"event\":\"").append(event)
                        .append("\",\"taskInstanceId\":\"").append(id).append("\"");
                if (exitCode != null) {
                    json.append(",\"exitCode\":").append(exitCode);
                }
                if (tailLog != null) {
                    json.append(",\"tailLog\":").append(escapeJson(tailLog));
                }
                if (failureReason != null) {
                    json.append(",\"failureReason\":").append(escapeJson(failureReason));
                }
                json.append("}");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(masterUrl + "/api/cluster/report"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + clusterToken)
                        .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                log.warn("[WorkerExec] 回报 master 失败：instance={}, event={}, error={}",
                        id, event, e.getMessage());
            }
        }

        private String escapeJson(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
    }
}
