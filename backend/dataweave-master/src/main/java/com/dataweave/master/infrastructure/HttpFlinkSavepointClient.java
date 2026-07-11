package com.dataweave.master.infrastructure;

import com.dataweave.master.application.FlinkSavepointClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@link FlinkSavepointClient} 的 Flink REST 实现（US3）。复用 master 既有 {@code cancelFlinkJob} 同款
 * {@code java.net.http.HttpClient}（无新依赖）。
 *
 * <p>协议：① {@code POST {rest}/jobs/{jobId}/stop}（body {@code {targetDirectory, drain:false}}）→ 202
 * {@code {"request-id": triggerId}}；② 轮询 {@code GET {rest}/jobs/{jobId}/savepoints/{triggerId}} 至
 * {@code status.id=COMPLETED}（取 {@code operation.location} 为 savepoint 路径）或 FAILED（取 failure-cause）。
 */
@Component
public class HttpFlinkSavepointClient implements FlinkSavepointClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final long pollIntervalMs;
    private final long pollTimeoutMs;

    public HttpFlinkSavepointClient(
            @Value("${streaming.savepoint.poll-interval-ms:2000}") long pollIntervalMs,
            @Value("${streaming.savepoint.poll-timeout-ms:120000}") long pollTimeoutMs) {
        this.pollIntervalMs = pollIntervalMs;
        this.pollTimeoutMs = pollTimeoutMs;
    }

    @Override
    public String stopWithSavepoint(String restEndpoint, String jobId, String targetDirectory)
            throws SavepointException {
        if (restEndpoint == null || restEndpoint.isBlank() || jobId == null || jobId.isBlank()) {
            throw new SavepointException("缺少 Flink 作业句柄（restEndpoint/jobId）");
        }
        String triggerId = triggerStop(restEndpoint, jobId, targetDirectory);
        return pollUntilComplete(restEndpoint, jobId, triggerId);
    }

    private String triggerStop(String rest, String jobId, String targetDirectory) {
        String body = targetDirectory != null && !targetDirectory.isBlank()
                ? "{\"targetDirectory\":\"" + targetDirectory + "\",\"drain\":false}"
                : "{\"drain\":false}";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(rest + "/jobs/" + jobId + "/stop"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new SavepointException("Flink stop 触发返回 HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode node = mapper.readTree(resp.body());
            String triggerId = node.has("request-id") ? node.get("request-id").asText() : null;
            if (triggerId == null || triggerId.isBlank()) {
                throw new SavepointException("Flink stop 响应缺 request-id: " + resp.body());
            }
            return triggerId;
        } catch (SavepointException e) {
            throw e;
        } catch (Exception e) {
            throw new SavepointException("触发 Flink stop-with-savepoint 失败: " + e.getMessage(), e);
        }
    }

    private String pollUntilComplete(String rest, String jobId, String triggerId) {
        long deadline = System.nanoTime() + Duration.ofMillis(pollTimeoutMs).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(rest + "/jobs/" + jobId + "/savepoints/" + triggerId))
                        .timeout(Duration.ofSeconds(10))
                        .GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 == 2) {
                    JsonNode node = mapper.readTree(resp.body());
                    String status = node.path("status").path("id").asText("");
                    if ("COMPLETED".equals(status)) {
                        JsonNode op = node.path("operation");
                        if (op.has("failure-cause")) {
                            throw new SavepointException("Flink savepoint 失败: "
                                    + op.path("failure-cause").path("stack-trace").asText("unknown"));
                        }
                        String location = op.path("location").asText(null);
                        if (location == null || location.isBlank()) {
                            throw new SavepointException("Flink savepoint 完成但无 location: " + resp.body());
                        }
                        return location;
                    }
                    // IN_PROGRESS → 继续轮询
                }
            } catch (SavepointException e) {
                throw e;
            } catch (Exception e) {
                throw new SavepointException("轮询 Flink savepoint 状态失败: " + e.getMessage(), e);
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new SavepointException("等待 Flink savepoint 被中断");
            }
        }
        throw new SavepointException("Flink savepoint 超时（" + pollTimeoutMs + "ms 内未完成）");
    }
}
