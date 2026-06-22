package com.dataweave.api.infrastructure;

import com.dataweave.master.application.TaskExecutionGateway;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Optional;

/**
 * {@link TaskExecutionGateway} 的分布式实现（task 3.2 / 3.3）：通过 WebClient 向目标 worker
 * 的 exec 端点 {@code POST /internal/worker/exec} 下发任务。
 *
 * <p>调度内核在事务外调用本网关；下发失败抛异常，由 {@code SchedulerKernel} CAS 回 WAITING 重派。
 * 共享 token 鉴权（{@code cluster.auth.token}）。
 *
 * <p>下发地址从 {@code worker_nodes} 注册表按 nodeCode 取 worker 上报的可达 {@code host}
 * （形如 {@code 127.0.0.1:8100}，含端口）。这样同机多 worker 可用不同端口区分；host 未含端口
 * 或节点缺失时回退到默认端口 8100（旧行为），保持兼容。
 */
@Component
@ConditionalOnProperty(name = "scheduler.mode", havingValue = "distributed")
public class DistributedTaskExecutionGateway implements TaskExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(DistributedTaskExecutionGateway.class);
    private static final int DEFAULT_WORKER_PORT = 8100;

    private final WebClient webClient;
    private final String clusterToken;
    private final String workerScheme;
    private final WorkerNodeRepository nodeRepository;

    public DistributedTaskExecutionGateway(WebClient.Builder webClientBuilder,
                                           @Value("${cluster.auth.token:}") String clusterToken,
                                           @Value("${cluster.worker.scheme:http}") String workerScheme,
                                           WorkerNodeRepository nodeRepository) {
        this.webClient = webClientBuilder.build();
        this.clusterToken = clusterToken;
        this.workerScheme = workerScheme;
        this.nodeRepository = nodeRepository;
    }

    /**
     * 解析目标 worker 的 exec URL：注册表 host 含端口则原样用，否则补默认端口；节点缺失/host 空
     * 回退用 nodeCode 当 host（旧行为）。
     */
    String resolveWorkerUrl(String nodeCode) {
        String host = Optional.ofNullable(nodeRepository.findByNodeCode(nodeCode).orElse(null))
                .map(WorkerNode::getHost)
                .filter(h -> h != null && !h.isBlank())
                .orElse(nodeCode);
        String authority = host.contains(":") ? host : host + ":" + DEFAULT_WORKER_PORT;
        return workerScheme + "://" + authority + "/internal/worker/exec";
    }

    @Override
    public void dispatch(DispatchCommand cmd) {
        String workerUrl = resolveWorkerUrl(cmd.workerNodeCode());

        Map<String, Object> body = Map.of(
                "taskInstanceId", cmd.taskInstanceId().toString(),
                "attempt", cmd.attempt(),
                "bizDate", cmd.bizDate() != null ? cmd.bizDate() : "",
                "content", cmd.content() != null ? cmd.content() : "",
                "timeoutSeconds", cmd.timeoutSeconds(),
                "taskType", cmd.taskType() != null ? cmd.taskType() : "SHELL",
                "locale", cmd.locale() != null ? cmd.locale() : ""
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
