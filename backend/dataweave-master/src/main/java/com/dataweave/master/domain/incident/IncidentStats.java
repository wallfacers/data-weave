package com.dataweave.master.domain.incident;

/**
 * 指挥中心实时数字（SC-010 一致性契约的载体）：快照与战况播报共用同一份 SQL 现算结果，
 * 永不从 incident_briefing.stats_json 佐证快照读取——播报文字可滞后（防抖），数字永远权威实时。
 *
 * @param active           未收口事故总数（open_key 非空）
 * @param agentWorking     Agent 正在处理（OPEN/ANALYZING/ACTING）
 * @param awaitingApproval 待人工审批（AWAITING_APPROVAL）
 * @param needsHuman       需人工介入（NEEDS_HUMAN）
 * @param resolvedToday    今日已收口（RESOLVED 且 closed_at 在今日）
 */
public record IncidentStats(int active, int agentWorking, int awaitingApproval, int needsHuman, int resolvedToday) {
}
