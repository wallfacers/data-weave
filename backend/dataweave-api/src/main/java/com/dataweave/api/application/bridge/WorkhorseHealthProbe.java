package com.dataweave.api.application.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * workhorse sidecar 健康探测：启动就绪后探一次 + 周期复探，结果缓存于 volatile，
 * {@link #isHealthy()} 读缓存（请求路径不阻塞）。仅 {@code agent.mode=workhorse} 时启用探测；
 * 非 workhorse 模式恒返回 false（编排据此走 mock，且不产生多余 HTTP）。
 *
 * <p>探测失败（sidecar 未起 / 无 key / 连接拒绝）即视为不健康，编排与诊断 SPI 自动降级 mock，
 * 保证 CI / fresh clone / 无 key 环境零依赖照常运行。
 */
@Component
public class WorkhorseHealthProbe implements WorkhorseHealth {

    private static final Logger log = LoggerFactory.getLogger(WorkhorseHealthProbe.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final String healthUrl;
    private final boolean enabled;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private volatile boolean healthy = false;
    private final AtomicBoolean degradeWarned = new AtomicBoolean(false);

    public WorkhorseHealthProbe(@Value("${agent.workhorse.base-url:http://127.0.0.1:8300}") String baseUrl,
                                @Value("${agent.workhorse.health-path:/health}") String healthPath,
                                @Value("${agent.mode:workhorse}") String mode) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = healthPath.startsWith("/") ? healthPath : "/" + healthPath;
        this.healthUrl = base + path;
        this.enabled = "workhorse".equalsIgnoreCase(mode);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (enabled) {
            probe();
        }
    }

    @Scheduled(fixedDelayString = "${agent.workhorse.probe-interval-ms:3000}",
            initialDelayString = "${agent.workhorse.probe-interval-ms:3000}")
    public void probe() {
        if (!enabled) {
            healthy = false;
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(healthUrl))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            if (ok && !healthy) {
                log.info("workhorse 大脑健康（{}），启用真实大脑", healthUrl);
                degradeWarned.set(false);
            }
            healthy = ok;
            if (!ok) {
                warnDegradeOnce(resp.statusCode() + " from " + healthUrl);
            }
        } catch (Exception e) {
            warnDegradeOnce(e.toString());
            healthy = false;
        }
    }

    /** 进入降级时仅打一次 WARN，避免周期探测刷屏；恢复健康后重置。 */
    private void warnDegradeOnce(String reason) {
        if (degradeWarned.compareAndSet(false, true)) {
            log.warn("workhorse 大脑不可用（{}），Agent 降级到规则引擎 mock。配好 sidecar/key 后自动恢复。", reason);
        }
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }
}
