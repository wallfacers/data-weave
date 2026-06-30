package com.dataweave.alert.domain;

import java.time.LocalDateTime;

/**
 * 数据健康事件（027）：规则无关的统一健康信号持久化（区别于规则绑定的 {@link AlertEvent}）。
 * 按 (tenantId, type, fingerprint) 去重合并。
 */
public class HealthEvent {

    private Long id;
    private Long tenantId;
    private String type;
    private String severity;
    private String fingerprint;
    private String refKind;
    private String refId;
    private String summary;
    private String contextJson;
    private Integer count;
    private LocalDateTime firstOccurredAt;
    private LocalDateTime lastOccurredAt;
    private LocalDateTime createdAt;
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getRefKind() { return refKind; }
    public void setRefKind(String refKind) { this.refKind = refKind; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getContextJson() { return contextJson; }
    public void setContextJson(String contextJson) { this.contextJson = contextJson; }
    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }
    public LocalDateTime getFirstOccurredAt() { return firstOccurredAt; }
    public void setFirstOccurredAt(LocalDateTime firstOccurredAt) { this.firstOccurredAt = firstOccurredAt; }
    public LocalDateTime getLastOccurredAt() { return lastOccurredAt; }
    public void setLastOccurredAt(LocalDateTime lastOccurredAt) { this.lastOccurredAt = lastOccurredAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
