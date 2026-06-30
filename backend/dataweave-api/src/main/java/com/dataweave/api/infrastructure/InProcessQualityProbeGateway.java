package com.dataweave.api.infrastructure;

import com.dataweave.master.application.DatasourceResolver;
import com.dataweave.master.application.DatasourceResolver.ResolvedConnection;
import com.dataweave.master.quality.application.QualityProbeGateway;
import com.dataweave.worker.domain.ExecutionContext;
import com.dataweave.worker.domain.TaskExecutor;
import com.dataweave.worker.domain.TaskExecutor.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

/**
 * {@link QualityProbeGateway} 的进程内实现（all-in-one 默认）：注入 worker 的
 * {@code QualityProbeExecutor} bean + {@code DatasourceResolver}，进程内同步
 * executor.execute 拿 {@code ExecutionResult}，从 {@code stdout} 读标量 measuredValue，
 * {@code skipped()} → {@code ProbeOutcome.skipped}。
 *
 * <p>不经 {@link com.dataweave.master.application.TaskExecutionGateway#dispatch}（异步 void），
 * 不经 {@code task_instance} 表——专用同步质量探针接缝。
 * distributed 实现留 TODO（v1 all-in-one 验证）。
 *
 * <p>由 api 模块承载（组合根，可引用 worker 模块的 TaskExecutor + master 模块的 DatasourceResolver）。
 */
@Component
@ConditionalOnProperty(name = "scheduler.mode", havingValue = "all-in-one", matchIfMissing = true)
public class InProcessQualityProbeGateway implements QualityProbeGateway {

    private static final Logger log = LoggerFactory.getLogger(InProcessQualityProbeGateway.class);

    private final DatasourceResolver datasourceResolver;
    /** 按 type() 索引的执行器 Map（Spring 注入所有 TaskExecutor bean，改建按 type）。 */
    private final Map<String, TaskExecutor> byType;

    public InProcessQualityProbeGateway(DatasourceResolver datasourceResolver,
                                         Map<String, TaskExecutor> executors) {
        this.datasourceResolver = datasourceResolver;
        Map<String, TaskExecutor> m = new java.util.HashMap<>();
        for (TaskExecutor e : executors.values()) {
            if (e.type() != null) {
                m.put(e.type().toUpperCase(), e);
            }
        }
        this.byType = m;
    }

    @Override
    public ProbeOutcome probe(long datasourceId, String measureSql, int timeoutSec) {
        TaskExecutor executor = byType.get("QUALITY_PROBE");
        if (executor == null) {
            return ProbeOutcome.skipped("无 QUALITY_PROBE 执行器（worker 未装载？）");
        }

        // 解析数据源
        ExecutionContext.DataSourceRef dsRef;
        try {
            ResolvedConnection resolved = datasourceResolver.resolve(datasourceId, "QUALITY_PROBE");
            dsRef = resolved != null
                    ? new ExecutionContext.DataSourceRef(
                    resolved.name(), resolved.typeCode(), resolved.jdbcUrl(),
                    resolved.username(), resolved.password(),
                    resolved.driverJarId(), resolved.driverClass(), resolved.storageKey())
                    : null;
        } catch (Exception e) {
            log.warn("[QualityProbe] datasourceId={} 解析失败: {}", datasourceId, e.getMessage());
            return ProbeOutcome.skipped("数据源解析失败：" + e.getMessage());
        }

        if (dsRef == null) {
            return ProbeOutcome.skipped("数据源不存在或不可用（datasourceId=" + datasourceId + "）");
        }

        // 构建 ExecutionContext（probe 专用上下文）
        ExecutionContext ctx = new ExecutionContext(
                measureSql,
                null,          // bizDate（质量 probe 不需要）
                1,             // attempt
                timeoutSec,
                "PROBE",       // runMode
                "QUALITY_PROBE",
                dsRef,
                null,          // shellEnvVars
                null,          // pythonConfigPath
                null           // sparkRef
        );

        // 同步执行（空 lineConsumer——probe 不需要实时日志回调）
        Consumer<String> noop = line -> {
        };
        ExecutionResult result = executor.execute(ctx, noop);

        if (result.skipped()) {
            return ProbeOutcome.skipped(result.message());
        }
        if (!result.success()) {
            return ProbeOutcome.error(result.message());
        }
        String measuredValue = result.stdout();
        return ProbeOutcome.measured(measuredValue != null ? measuredValue : "");
    }
}
