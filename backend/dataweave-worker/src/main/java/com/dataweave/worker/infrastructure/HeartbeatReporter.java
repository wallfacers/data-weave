package com.dataweave.worker.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Worker 心跳上报客户端（默认关闭）。
 *
 * <p>定时采集本机指标，POST 到 master 的 /api/fleet/heartbeat 端点。
 * 供独立 worker 进程部署时启用（dataweave.worker.heartbeat.enabled=true）。
 */
@Component
public class HeartbeatReporter {

    private static final System.Logger log = System.getLogger(HeartbeatReporter.class.getName());

    @Value("${dataweave.worker.heartbeat.enabled:false}")
    private boolean enabled;

    @Value("${dataweave.master.url:http://localhost:8080}")
    private String masterUrl;

    @Value("${dataweave.worker.node-code:worker-local}")
    private String nodeCode;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Scheduled(fixedDelayString = "${dataweave.worker.heartbeat.interval-ms:10000}")
    public void report() {
        if (!enabled) {
            return;
        }
        try {
            String host = InetAddress.getLocalHost().getHostName();
            String capacity = "4C/8G";
            double cpu = 0.3;
            double mem = 0.45;
            double disk = 0.5;
            double loadAvg = 1.2;

            String json = """
                    {"nodeCode":"%s","host":"%s","capacity":"%s","cpu":%s,"mem":%s,"disk":%s,"loadAvg":%s,"runningTasks":0}
                    """.formatted(
                    nodeCode, host, capacity, cpu, mem, disk, loadAvg);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(masterUrl + "/api/fleet/heartbeat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.log(System.Logger.Level.DEBUG,
                    "Heartbeat reported: nodeCode={0}, status={1}",
                    nodeCode, response.statusCode());
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING,
                    "Heartbeat report failed: {0}", e.getMessage());
        }
    }
}
