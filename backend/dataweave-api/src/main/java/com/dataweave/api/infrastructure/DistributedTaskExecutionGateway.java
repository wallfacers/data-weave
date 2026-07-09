package com.dataweave.api.infrastructure;

import com.dataweave.master.application.DatasourceResolver;
import com.dataweave.master.application.DatasourceResolver.ResolvedConnection;
import com.dataweave.master.application.TaskExecutionGateway;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.PostConstruct;

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
 *
 * <p><b>contracts C4.2（FR-007 完整 ctx）</b>：api 层（持有 {@link DatasourceResolver}）解析数据源 →
 * 把解析后连接信息（SQL DataSourceRef 字段 / SHELL env / PYTHON config 内容 / SPARK SparkClusterRef 字段）
 * 序列化进 exec body 的 {@code datasource} 字段；worker 侧反序列化消费——**worker 不新增 DB 依赖**，与 all-in-one 对称。
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
    private final DatasourceResolver datasourceResolver;

    public DistributedTaskExecutionGateway(@Qualifier("dispatchWebClientBuilder") WebClient.Builder webClientBuilder,
                                           @Value("${cluster.auth.token:}") String clusterToken,
                                           @Value("${cluster.worker.scheme:http}") String workerScheme,
                                           WorkerNodeRepository nodeRepository,
                                           DatasourceResolver datasourceResolver) {
        this.webClient = webClientBuilder.build();
        this.clusterToken = clusterToken;
        this.workerScheme = workerScheme;
        this.nodeRepository = nodeRepository;
        this.datasourceResolver = datasourceResolver;
    }

    /**
     * 防御性检查：非 HTTPS + 非 loopback 时告警——distributed exec body 含凭据（SQL username/password、
     * PYTHON 配置等），明文 HTTP 传输仅在同机 loopback 场景可接受。生产部署应配 cluster.worker.scheme=https
     * 或启用 worker 间 mTLS。此检查不阻止启动，仅告警。
     */
    @PostConstruct
    void warnOnPlaintextScheme() {
        if (!"https".equals(workerScheme)) {
            log.warn("cluster.worker.scheme={} —— distributed exec body 含凭据，建议仅 loopback 使用 HTTP，"
                    + "其他场景请设为 https", workerScheme);
        }
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
        String taskType = cmd.taskType() != null ? cmd.taskType() : "SHELL";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskInstanceId", cmd.taskInstanceId().toString());
        body.put("attempt", cmd.attempt());
        body.put("bizDate", cmd.bizDate() != null ? cmd.bizDate() : "");
        body.put("content", cmd.content() != null ? cmd.content() : "");
        body.put("timeoutSeconds", cmd.timeoutSeconds());
        body.put("taskType", taskType);
        body.put("locale", cmd.locale() != null ? cmd.locale() : "");
        // SPARK 内容形态（任务属性，非数据源）：顶层透传，worker 合成 SparkSubmitRef
        if (cmd.sparkMode() != null) {
            body.put("sparkMode", cmd.sparkMode());
        }
        if (cmd.jarRef() != null) {
            body.put("jarRef", cmd.jarRef());
        }
        if (cmd.mainClass() != null) {
            body.put("mainClass", cmd.mainClass());
        }
        // 通用引擎内容形态（FLINK/DATAX/SEATUNNEL）：顶层透传，worker 合成 EngineSubmitRef
        if (cmd.engineMode() != null) {
            body.put("engineMode", cmd.engineMode());
        }
        if (cmd.engineJarRef() != null) {
            body.put("engineJarRef", cmd.engineJarRef());
        }
        if (cmd.engineMainClass() != null) {
            body.put("engineMainClass", cmd.engineMainClass());
        }

        // C4.2：解析数据源 → 序列化连接信息进 body（worker 不新增 DB 依赖）
        Map<String, Object> dsInfo = resolveDatasourceForWire(cmd.datasourceId(), taskType);
        if (dsInfo != null) {
            body.put("datasource", dsInfo);
        }

        log.debug("[DistDispatch] → {} instance={} type={}", workerUrl, cmd.taskInstanceId(), taskType);

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

    /** 解析数据源并序列化为 over-wire 友好的扁平 map（contracts C4.2）。 */
    private Map<String, Object> resolveDatasourceForWire(Long datasourceId, String taskType) {
        if (datasourceId == null || datasourceResolver == null) {
            return null;
        }
        try {
            if ("PYTHON".equals(taskType)) {
                // PYTHON：序列化配置内容（worker 侧落盘），不传 master 临时文件路径（跨机无效）
                String json = datasourceResolver.pythonConfigJson(datasourceId);
                return json == null ? null : Map.of("taskType", taskType, "pythonConfigJson", json);
            }
            ResolvedConnection r = datasourceResolver.resolve(datasourceId, taskType);
            return serializeResolved(r, taskType);
        } catch (Exception e) {
            log.warn("[DistDispatch] 数据源解析失败 (id={}): {}", datasourceId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> serializeResolved(ResolvedConnection r, String taskType) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskType", taskType);
        switch (taskType) {
            case "SQL" -> {
                m.put("name", r.name());
                m.put("typeCode", r.typeCode());
                m.put("jdbcUrl", r.jdbcUrl());
                m.put("username", r.username());
                m.put("password", r.password());
                m.put("driverJarId", r.driverJarId());
                m.put("driverClass", r.driverClass());
                m.put("storageKey", r.storageKey());
            }
            case "SHELL" -> m.put("shellEnvVars", r.shellEnvVars());
            case "SPARK" -> {
                if (r.spark() != null) {
                    m.put("sparkHome", r.spark().sparkHome());
                    m.put("master", r.spark().master());
                    m.put("deployMode", r.spark().deployMode());
                    m.put("queue", r.spark().queue());
                    m.put("conf", r.spark().conf());
                }
            }
            case "FLINK", "DATAX", "SEATUNNEL" -> {
                if (r.engine() != null) {
                    m.put("engineKind", r.engine().kind());
                    m.put("engineHome", r.engine().engineHome());
                    m.put("engineProps", r.engine().props());
                }
            }
            default -> {
                return null;
            }
        }
        return m;
    }

    /** 下发失败时抛出，由 SchedulerKernel 捕获后 CAS 回 WAITING。 */
    public static class DispatchException extends RuntimeException {
        public DispatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
