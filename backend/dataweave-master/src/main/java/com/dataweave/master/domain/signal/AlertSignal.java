package com.dataweave.master.domain.signal;

import java.time.Instant;
import java.util.Map;

/**
 * 告警信号：master 侧 publish, alert 侧 {@code @EventListener} 消费。
 *
 * <p>master 编译期不依赖 alert 模块（用框架 {@link org.springframework.context.ApplicationEventPublisher}）；
 * alert 模块依赖 master, 通过 {@code @EventListener} 订阅。
 */
public class AlertSignal {

    private final Type type;
    private final long tenantId;
    private final String fingerprintHint;
    private final String severityHint;
    private final Map<String, Object> context;
    private final Instant occurredAt;

    public AlertSignal(Type type, long tenantId, String fingerprintHint,
                       String severityHint, Map<String, Object> context) {
        this.type = type;
        this.tenantId = tenantId;
        this.fingerprintHint = fingerprintHint;
        this.severityHint = severityHint;
        this.context = context;
        this.occurredAt = Instant.now();
    }

    public Type getType() { return type; }
    public long getTenantId() { return tenantId; }
    public String getFingerprintHint() { return fingerprintHint; }
    public String getSeverityHint() { return severityHint; }
    public Map<String, Object> getContext() { return context; }
    public Instant getOccurredAt() { return occurredAt; }

    public enum Type {
        TASK_FAILED,
        TASK_TIMEOUT,
        SLA_BREACH,
        WORKFLOW_STATE,
        NODE_OFFLINE,
        METRIC_BREACH,
        /** 预留：022 数据质量断言 FAIL 时发射 */
        QUALITY_FAILED
    }
}
