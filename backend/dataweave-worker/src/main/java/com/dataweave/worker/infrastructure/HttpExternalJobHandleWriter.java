package com.dataweave.worker.infrastructure;

import com.dataweave.worker.domain.ExternalJobHandleWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * 外部作业句柄 HTTP 回写实现（060 FR-023）：worker 侧把 Flink detached 提交解析出的
 * external_job_handle（JobID+REST 端点）POST 回 master 持久化到 task_instance。
 *
 * <p>复用 {@code WorkerExecController} 同款 {@code dataweave.master.url} + {@code cluster.auth.token}
 * 与 java.net.http 客户端。端点：{@code POST {masterUrl}/api/cluster/instances/{id}/external-job-handle}
 *（JWT 豁免的 worker 面，cluster-token 鉴权），body=句柄 JSON。
 *
 * <p>best-effort：回写失败不抛断执行（记日志）；失败仅意味着该实例 failover 时无法 reattach，
 * 会走业务重试重新提交（不双跑：集群侧旧 job 由 max-runtime/人工兜底，句柄缺失不产生第二个受控实例）。
 */
@Component
public class HttpExternalJobHandleWriter implements ExternalJobHandleWriter {

    private static final System.Logger log = System.getLogger(HttpExternalJobHandleWriter.class.getName());

    @Value("${dataweave.master.url:http://localhost:8000}")
    private String masterUrl;

    @Value("${cluster.auth.token:}")
    private String clusterToken;

    @Override
    public void write(UUID taskInstanceId, String handle) {
        if (taskInstanceId == null || handle == null || handle.isBlank()) {
            return;
        }
        String url = masterUrl + "/api/cluster/instances/" + taskInstanceId + "/external-job-handle";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(handle))
                    .timeout(Duration.ofSeconds(10));
            if (clusterToken != null && !clusterToken.isBlank()) {
                builder.header("Authorization", "Bearer " + clusterToken);
            }
            HttpRequest request = builder.build();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.log(System.Logger.Level.WARNING,
                        "external_job_handle 回写 HTTP {0} instance={1}", resp.statusCode(), taskInstanceId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.log(System.Logger.Level.WARNING, "external_job_handle 回写被中断 instance={0}", taskInstanceId);
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING,
                    "external_job_handle 回写失败 instance={0}: {1}", taskInstanceId, e.getMessage());
        }
    }
}
