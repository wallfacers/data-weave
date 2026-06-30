package com.dataweave.alert.domain;

import java.time.LocalDateTime;

/**
 * 事件订阅（027）：命中事件经 {@code channelId}（复用 026 alert_channel）分发。
 */
public class EventSubscription {

    private Long id;
    private Long tenantId;
    private Long subscriberId;
    private String typeFilter;   // null/空 = 全部类型
    private String minSeverity;  // null = 不限
    private String refKind;      // null = 全部资产维度
    private String refId;        // null = 全部
    private Long channelId;      // 目标通道（alert_channel.id）
    private Integer enabled;
    private LocalDateTime createdAt;
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getSubscriberId() { return subscriberId; }
    public void setSubscriberId(Long subscriberId) { this.subscriberId = subscriberId; }
    public String getTypeFilter() { return typeFilter; }
    public void setTypeFilter(String typeFilter) { this.typeFilter = typeFilter; }
    public String getMinSeverity() { return minSeverity; }
    public void setMinSeverity(String minSeverity) { this.minSeverity = minSeverity; }
    public String getRefKind() { return refKind; }
    public void setRefKind(String refKind) { this.refKind = refKind; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
