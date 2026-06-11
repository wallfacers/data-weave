package com.dataweave.api.infrastructure;

import com.dataweave.master.application.TaskExecutionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * {@link TaskExecutionGateway} 的分布式实现（task 3.2 / 3.3）：通过 WebClient 向目标 worker
 * 的 exec 端点 {@code POST /internal/worker/exec} 下发任务。
 *
 * <p>调度内核在事务外调用本网关；下发失败抛异常，由 {@code SchedulerKernel} CAS 回 WAITING 重派。
 * 共享 token 鉴权（{@code cluster.auth.token}）。
 */
@Component
@ConditionalOnProperty(name = "scheduler.mode", havingValue = "distributed")
public class DistributedTaskExecutionGateway implements TaskExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(DistributedTaskExecutionGateway.class);

    private final WebClient webClient;
    private final String clusterToken;
    private final String workerScheme;

    public DistributedTaskExecutionGateway(WebClient.Builder webClientBuilder,
                                           @Value("${cluster.auth.token:}") String clusterToken,
                                           @Value("${cluster.worker.scheme:http}") String workerScheme) {
        this.webClient = webClientBuilder.build();
        this.clusterToken = clusterToken;
        this.workerScheme = workerScheme;
    }

    @Override
    public void dispatch(DispatchCommand cmd) {
        String workerUrl = workerScheme + "://" + cmd.workerNodeCode() + ":8081/internal/worker/exec";

        Map<String, Object> body = Map.of(
                "taskInstanceId", cmd.taskInstanceId().toString(),
                "attempt", cmd.attempt(),
                "bizDate", cmd.bizDate() != null ? cmd.bizDate() : "",
                "content", cmd.content() != null ? cmd.content() : "",
                "timeoutSeconds", cmd.timeoutSeconds(),
                "taskType", cmd.taskType() != null ? cmd.taskType() : "SHELL"
        );

        log.debug("[DistDispatch] → {} instance={}", workerUrl, cmd.taskInstanceId());

        try {
            String response = webClient.post()
                    .uri(workerUrl)
                    .header("Authorization", "Bearer " + clusterToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.debug("[DistDispatch] ← {} response={}", workerUrl, response);
        } catch (Exception e) {
            log.warn("[DistDispatch] 下发失败 instance={} worker={}: {}",
                    cmd.taskInstanceId(), cmd.workerNodeCode(), e.getMessage());
            throw new DispatchException("下发到 " + cmd.workerNodeCode() + " 失败: " + e.getMessage(), e);
        }
    }

    /** 下发失败时抛出，由 SchedulerKernel 捕获后 CAS 回 WAITING。 */
    public static class DispatchException extends RuntimeException {
        public DispatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
