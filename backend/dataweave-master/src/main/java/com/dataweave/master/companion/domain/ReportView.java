package com.dataweave.master.companion.domain;

import java.time.LocalDateTime;

/**
 * 巡检汇报视图（契约 ReportView，SSE/REST 共用）。
 *
 * <pre>
 * ReportView = {id, domain, severity, title, summary, detail, aggregateCount, status, closedBy, createdAt}
 * </pre>
 * {@code detail} 为 detail_json 原文（结构化明细，前端解析渲染关联对象）。
 */
public record ReportView(
        long id,
        String domain,
        String severity,
        String title,
        String summary,
        String detail,
        int aggregateCount,
        String status,
        String closedBy,
        LocalDateTime createdAt
) {
    public static ReportView from(PatrolReport r) {
        return new ReportView(r.id(), r.domain(), r.severity(), r.title(), r.summary(), r.detailJson(),
                r.aggregateCount(), r.status(), r.closedBy(), r.createdAt());
    }
}
