package com.dataweave.master.quality.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 跨模块告警信号（022→021 接缝 D4；021 未合并时 022 按 signal-seam.md 契约自建，合并期平凡去重）。
 *
 * <p>信号产生方在 master application 通过 Spring {@code ApplicationEventPublisher} publish；
 * 消费方在 021 alert 引擎 {@code @EventListener} 匹配 {@code signal_source} 的告警规则。
 *
 * @param type            信号类型
 * @param tenantId        租户
 * @param fingerprintHint 参与 021 fingerprint 去重（如 taskId/workflowId/datasetRef/metricKey）
 * @param severityHint    信号侧建议 severity（021 规则可覆盖）
 * @param context         载荷（如 ruleId/runId/resultId/datasetRef/measuredValue/expected/action）
 * @param occurredAt      事件时刻
 */
public record AlertSignal(
        Type type,
        long tenantId,
        String fingerprintHint,
        String severityHint,
        Map<String, Object> context,
        Instant occurredAt) {

    /** 信号类型（完整集合，021 契约）。022 只产生 QUALITY_FAILED。 */
    public enum Type {
        TASK_FAILED,
        TASK_TIMEOUT,
        SLA_BREACH,
        WORKFLOW_STATE,
        NODE_OFFLINE,
        METRIC_BREACH,
        /** 数据质量断言 FAIL（产生方=022；消费方=021）。 */
        QUALITY_FAILED,
        ASSET_CHANGED
    }
}
