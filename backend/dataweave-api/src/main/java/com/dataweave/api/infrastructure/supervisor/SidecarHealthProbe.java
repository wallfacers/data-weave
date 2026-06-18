package com.dataweave.api.infrastructure.supervisor;

import com.dataweave.api.application.supervisor.SupervisorCore.Health;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * workhorse sidecar 的 {@code /health} 探测 IO 壳（变更 dataweave-managed-sidecar，tasks 2.2）。
 *
 * <p>决策核心 {@link com.dataweave.api.application.supervisor.SupervisorCore} 平台/网络无关，
 * 健康探测这一 IO 副作用落在本壳：复用既有 {@link WebClient.Builder}（见 {@code WebClientConfig}），
 * 对目标 {@code base-url + health-path} 发 GET，2xx 即 {@link Health#UP}，超时/连接拒绝/非 2xx 皆 {@link Health#DOWN}。
 *
 * <p>不接管「健康收敛等待」——那由 supervisor 控制循环按退避反复调用本探测实现（design D7）。
 */
public final class SidecarHealthProbe {

    private final WebClient webClient;
    private final String healthPath;
    private final Duration timeout;

    public SidecarHealthProbe(WebClient.Builder builder, String baseUrl, String healthPath, Duration timeout) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.healthPath = healthPath;
        this.timeout = timeout;
    }

    /** 探测一次 {@code /health}：2xx → UP；任何异常（超时/拒绝/非 2xx）→ DOWN（绝不抛）。 */
    public Health probe() {
        try {
            return webClient.get().uri(healthPath)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(timeout)
                    .map(resp -> resp.getStatusCode().is2xxSuccessful() ? Health.UP : Health.DOWN)
                    .onErrorReturn(Health.DOWN)
                    .blockOptional()
                    .orElse(Health.DOWN);
        } catch (Exception e) {
            return Health.DOWN;
        }
    }
}
