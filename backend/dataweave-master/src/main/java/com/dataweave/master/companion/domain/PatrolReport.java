package com.dataweave.master.companion.domain;

import java.time.LocalDateTime;

/**
 * 巡检汇报（{@code patrol_report} 表）：一轮巡检的结构化产出。
 *
 * <p>项目级共享（clarify 决议）：任一成员关闭后对项目内全员消失、持久且不自动重现。
 * {@code severity=INFO} 含"未完成"汇报（巡检失败兜底，FR-008/SC-007 零静默丢失）。
 * 关联对象已消失时由 {@code detailJson} 中的快照名兜底展示（边界用例）。
 */
public record PatrolReport(
        long id,
        long tenantId,
        long projectId,
        Long runId,                 // 产出来源（执行历史↔汇报关联，US4-AS2）；允许 NULL 兜底
        String domain,              // 冗余自 routine，查询友好
        String severity,            // 见 ReportSeverities
        String title,
        String summary,             // 摘要（管家播报文案来源）
        String detailJson,          // 结构化明细：关联对象(type+id+name)/聚合计数/建议动作
        int aggregateCount,         // 同领域聚合窗口内异常条数（FR-011）
        String status,              // 见 ReportStatuses
        String closedBy,            // 关闭人显示名（项目级共享处置留痕）
        LocalDateTime closedAt,
        LocalDateTime createdAt
) {
}
