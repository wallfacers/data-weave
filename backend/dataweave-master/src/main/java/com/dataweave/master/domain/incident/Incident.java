package com.dataweave.master.domain.incident;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Incident 工单本体 —— 可变实体（含全套审计列：tenant_id, project_id, created_by/at, updated_by/at, deleted, version）。
 *
 * <p>字段 = data-model.md 表 1（043）。diagnosis_json / proposal_json 本期恒 NULL（FR-013 槽位预留）。
 */
@Table("incident")
public class Incident {

    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    private String signature;
    private String activeKey;
    private String title;
    private String severity;
    private String state;
    private String sourceKind;
    private String sourceRefId;
    private String sourceRefName;
    private UUID workflowInstanceId;
    private int occurrenceCount;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private Integer blastRadius;
    private LocalDateTime timeBudgetAt;
    private String suppressReason;
    private String resolutionKind;
    private String healByType;        // 064 愈合条件——恢复信号事件类型
    private String healByRefId;       // 064 愈合条件——恢复信号引用 ID
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;
    private String diagnosisJson;
    private String proposalJson;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int deleted;
    private int version;

    public Incident() {}

    // ── auto-generated ─────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getActiveKey() { return activeKey; }
    public void setActiveKey(String activeKey) { this.activeKey = activeKey; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getSourceKind() { return sourceKind; }
    public void setSourceKind(String sourceKind) { this.sourceKind = sourceKind; }

    public String getSourceRefId() { return sourceRefId; }
    public void setSourceRefId(String sourceRefId) { this.sourceRefId = sourceRefId; }

    public String getSourceRefName() { return sourceRefName; }
    public void setSourceRefName(String sourceRefName) { this.sourceRefName = sourceRefName; }

    public UUID getWorkflowInstanceId() { return workflowInstanceId; }
    public void setWorkflowInstanceId(UUID workflowInstanceId) { this.workflowInstanceId = workflowInstanceId; }

    public int getOccurrenceCount() { return occurrenceCount; }
    public void setOccurrenceCount(int occurrenceCount) { this.occurrenceCount = occurrenceCount; }

    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(LocalDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public Integer getBlastRadius() { return blastRadius; }
    public void setBlastRadius(Integer blastRadius) { this.blastRadius = blastRadius; }

    public LocalDateTime getTimeBudgetAt() { return timeBudgetAt; }
    public void setTimeBudgetAt(LocalDateTime timeBudgetAt) { this.timeBudgetAt = timeBudgetAt; }

    public String getSuppressReason() { return suppressReason; }
    public void setSuppressReason(String suppressReason) { this.suppressReason = suppressReason; }

    public String getResolutionKind() { return resolutionKind; }
    public void setResolutionKind(String resolutionKind) { this.resolutionKind = resolutionKind; }

    public String getHealByType() { return healByType; }
    public void setHealByType(String healByType) { this.healByType = healByType; }

    public String getHealByRefId() { return healByRefId; }
    public void setHealByRefId(String healByRefId) { this.healByRefId = healByRefId; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }

    public String getDiagnosisJson() { return diagnosisJson; }
    public void setDiagnosisJson(String diagnosisJson) { this.diagnosisJson = diagnosisJson; }

    public String getProposalJson() { return proposalJson; }
    public void setProposalJson(String proposalJson) { this.proposalJson = proposalJson; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public int getDeleted() { return deleted; }
    public void setDeleted(int deleted) { this.deleted = deleted; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
