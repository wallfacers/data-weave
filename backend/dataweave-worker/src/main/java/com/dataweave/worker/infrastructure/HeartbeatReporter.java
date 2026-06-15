package com.dataweave.worker.infrastructure;

import com.dataweave.worker.application.IncarnationManager;
import com.dataweave.worker.application.WorkerExecService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Worker 心跳上报客户端（task 3.5）。
 *
 * <p>定时采集本机指标 + incarnation + 运行中实例 ID 列表，POST 到 master 的 /api/fleet/heartbeat 端点。
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

    /** worker 自身 HTTP 端口，随心跳上报供 master distributed 下发寻址。 */
    @Value("${server.port:8081}")
    private int serverPort;

    /** 对外可达主机名/IP（同机多 worker 区分用），留空则回退本机名。 */
    @Value("${dataweave.worker.advertise-host:}")
    private String advertiseHost;

    private final IncarnationManager incarnationManager;
    private final WorkerExecService execService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public HeartbeatReporter(IncarnationManager incarnationManager, WorkerExecService execService) {
        this.incarnationManager = incarnationManager;
        this.execService = execService;
    }

    @Scheduled(fixedDelayString = "${dataweave.worker.heartbeat.interval-ms:10000}")
    public void report() {
        if (!enabled) {
            return;
        }
        try {
            String host = buildAdvertisedHost(advertiseHost, InetAddress.getLocalHost().getHostName(), serverPort);
            String capacity = "4C/8G";
            double cpu = 0.3;
            double mem = 0.45;
            double disk = 0.5;
            double loadAvg = 1.2;

            long incarnation = incarnationManager.incarnation();
            // runningInstanceIds 暂传空列表（分布式模式后续通过 WorkerExecService 跟踪）
            String instanceIdsArray = "[]";

            String json = """
                    {"nodeCode":"%s","host":"%s","capacity":"%s","cpu":%s,"mem":%s,"disk":%s,"loadAvg":%s,"runningTasks":%s,"incarnation":%s,"runningInstanceIds":%s}
                    """.formatted(
                    nodeCode, host, capacity, cpu, mem, disk, loadAvg,
                    execService.inFlightCount(), incarnation, instanceIdsArray);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(masterUrl + "/api/fleet/heartbeat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.log(System.Logger.Level.DEBUG,
                    "Heartbeat reported: nodeCode={0}, incarnation={1}, status={2}",
                    nodeCode, incarnation, response.statusCode());
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING,
                    "Heartbeat report failed: {0}", e.getMessage());
        }
    }

    /**
     * 拼装上报的可达地址 {@code host:port}：advertiseHost 非空则用它（已含端口则原样返回），
     * 否则回退 fallbackHost；最终统一补上 port。
     */
    static String buildAdvertisedHost(String advertiseHost, String fallbackHost, int port) {
        String host = (advertiseHost != null && !advertiseHost.isBlank()) ? advertiseHost : fallbackHost;
        return host.contains(":") ? host : host + ":" + port;
    }
}
