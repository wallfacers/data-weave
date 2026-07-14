package com.dataweave.master.domain.incident;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 修复提案（代码缺陷类的谨慎处置载体，见 ProposalStatuses）。全量新内容而非 diff，
 * 与 push 幂等覆盖语义一致；baseVersionNo 陈旧性防护——发布前必须仍等于 task_def.current_version_no。
 */
public record IncidentProposal(
        UUID id,
        UUID incidentId,
        long taskDefId,
        int baseVersionNo,
        String proposedContent,   // 全量新脚本内容
        String changeSummary,
        String evidenceJson,      // 证据包：诊断依据/关键日志行/预期效果
        String status,            // 见 ProposalStatuses
        Long agentActionId,       // 关联 agent_action（闸门/审批单）
        Integer publishedVersionNo,
        Integer rollbackVersionNo,// 回滚也是新快照，版本只进不退
        Long approvedBy,
        LocalDateTime approvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
