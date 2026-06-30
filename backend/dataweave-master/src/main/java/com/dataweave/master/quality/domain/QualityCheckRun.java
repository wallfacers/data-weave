package com.dataweave.master.quality.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 一次质量执行（quality_check_run，FR-002/FR-004）。
 *
 * <p>三入口（{@link CheckTrigger}）共享此模型。{@code taskInstanceId} 仅 POST_TASK 入口关联（其余 null）。
 * {@code status} 经 {@link CheckStatus#aggregate} 归约；{@code ERROR}=基础设施失败（FR-007）。
 * {@code ruleSnapshotJson} 快照参与规则定义（进行中编辑不影响本 run，research D11）。
 */
@Table("quality_check_run")
public class QualityCheckRun {

    @Id
    private Long id;
    private Long tenantId;
    private String datasetRef;
    private String trigger;
    private UUID taskInstanceId;
    private String status;
    private Integer sampled;
    private Integer ruleCount;
    private Integer failCount;
    private Integer blocked;
    private String ruleSnapshotJson;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public QualityCheckRun() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getDatasetRef() { return datasetRef; }
    public void setDatasetRef(String datasetRef) { this.datasetRef = datasetRef; }
    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }
    public UUID getTaskInstanceId() { return taskInstanceId; }
    public void setTaskInstanceId(UUID taskInstanceId) { this.taskInstanceId = taskInstanceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSampled() { return sampled; }
    public void setSampled(Integer sampled) { this.sampled = sampled; }
    public Integer getRuleCount() { return ruleCount; }
    public void setRuleCount(Integer ruleCount) { this.ruleCount = ruleCount; }
    public Integer getFailCount() { return failCount; }
    public void setFailCount(Integer failCount) { this.failCount = failCount; }
    public Integer getBlocked() { return blocked; }
    public void setBlocked(Integer blocked) { this.blocked = blocked; }
    public String getRuleSnapshotJson() { return ruleSnapshotJson; }
    public void setRuleSnapshotJson(String ruleSnapshotJson) { this.ruleSnapshotJson = ruleSnapshotJson; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
