package com.dataweave.master.domain.incident;

import java.time.LocalDateTime;

/**
 * 战况播报（每项目最新一行）。statsJson 仅作报告佐证快照——接口返回的实时数字永远另由 SQL 现算
 * （SC-010 一致性契约：数字 SQL、叙述 LLM 分离）。
 */
public record IncidentBriefing(
        Long id,
        long tenantId,
        long projectId,
        String summaryLine,   // LLM 一句话综述
        String reportMd,      // 完整接班报告（Markdown）
        String statsJson,     // 生成时点计数快照（佐证用，非权威）
        LocalDateTime generatedAt
) {
}
