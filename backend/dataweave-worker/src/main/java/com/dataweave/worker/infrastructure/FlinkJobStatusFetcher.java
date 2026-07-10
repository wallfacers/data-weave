package com.dataweave.worker.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flink 作业状态抓取（060 FR-024/025）：GET {restEndpoint}/jobs/{jobId} → job state。
 *
 * <p>抽成接口以便单测注入假实现（不依赖真 Flink 集群）。生产用 {@link #http()}（java.net.http）。
 *
 * <p>约定：
 * <ul>
 *   <li>返回 Flink job state 字符串（RUNNING/FINISHED/FAILED/CANCELED/…，大写）。</li>
 *   <li>作业不存在（HTTP 404）→ 返回 {@code null}（reattach 据此判断需重新提交）。</li>
 *   <li>网络/其它 HTTP 错误 → 抛 {@link IOException}（轮询侧计入连续失败）。</li>
 * </ul>
 */
@FunctionalInterface
public interface FlinkJobStatusFetcher {

    /** 抓取 job state；作业不存在返回 null；网络错误抛 IOException。 */
    String fetchState(String restEndpoint, String jobId) throws IOException;

    /** Flink REST job status JSON 里的 state 字段：{@code "state":"RUNNING"}。 */
    Pattern STATE_PATTERN = Pattern.compile("\"state\"\\s*:\\s*\"([A-Za-z_]+)\"");

    /** 生产实现：java.net.http GET，正则解析 state（零 Jackson 依赖，避 Jackson2/3 歧义）。 */
    static FlinkJobStatusFetcher http() {
        return (restEndpoint, jobId) -> {
            String url = restEndpoint + "/jobs/" + jobId;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            try {
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 404) {
                    return null; // 作业不存在
                }
                if (code != 200) {
                    throw new IOException("Flink REST HTTP " + code + " for " + url);
                }
                Matcher m = STATE_PATTERN.matcher(resp.body());
                return m.find() ? m.group(1).toUpperCase() : null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Flink REST 轮询被中断", e);
            }
        };
    }
}
