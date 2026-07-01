package com.dataweave.alert.domain;

import java.time.LocalDateTime;

/**
 * 告警规则定义：绑定信号源 + 评估条件 + 去抖/抑制/自动恢复。
 */
public class AlertRule {

    private Long id;
    private Long tenantId;
    private Long projectId;
    private String name;
    private String description;
    private Integer enabled;
    private String signalSource;
    private String evalMode;
    private Integer evalIntervalSec;
    private String conditionJson;
    private String severity;
    private Integer forDuration;
    private String dedupKeyTemplate;
    private Integer suppressWindowSec;
    private Integer autoResolve;
    private String labelsJson;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    // -- getters --
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public String getSignalSource() { return signalSource; }
    public void setSignalSource(String signalSource) { this.signalSource = signalSource; }
    public String getEvalMode() { return evalMode; }
    public void setEvalMode(String evalMode) { this.evalMode = evalMode; }
    public Integer getEvalIntervalSec() { return evalIntervalSec; }
    public void setEvalIntervalSec(Integer evalIntervalSec) { this.evalIntervalSec = evalIntervalSec; }
    public String getConditionJson() { return conditionJson; }
    public void setConditionJson(String conditionJson) { this.conditionJson = conditionJson; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public Integer getForDuration() { return forDuration; }
    public void setForDuration(Integer forDuration) { this.forDuration = forDuration; }
    public String getDedupKeyTemplate() { return dedupKeyTemplate; }
    public void setDedupKeyTemplate(String dedupKeyTemplate) { this.dedupKeyTemplate = dedupKeyTemplate; }
    public Integer getSuppressWindowSec() { return suppressWindowSec; }
    public void setSuppressWindowSec(Integer suppressWindowSec) { this.suppressWindowSec = suppressWindowSec; }
    public Integer getAutoResolve() { return autoResolve; }
    public void setAutoResolve(Integer autoResolve) { this.autoResolve = autoResolve; }
    public String getLabelsJson() { return labelsJson; }
    public void setLabelsJson(String labelsJson) { this.labelsJson = labelsJson; }
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
