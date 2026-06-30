package com.dataweave.master.domain.asset;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 资产/指标订阅（租户级 + 订阅用户）。变更时反查订阅者 publish ASSET_CHANGED → 021。
 * 以 (tenant_id, subscriber_user_id, target_type, target_id) 唯一。
 */
@Table("asset_subscription")
public class AssetSubscription {

    @Id
    private Long id;
    private Long tenantId;
    private Long subscriberUserId;
    private String targetType;      // ASSET / METRIC
    private Long targetId;
    private String changeFilter;    // schema,quality,freshness（CSV）
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public AssetSubscription() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getSubscriberUserId() { return subscriberUserId; }
    public void setSubscriberUserId(Long subscriberUserId) { this.subscriberUserId = subscriberUserId; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public String getChangeFilter() { return changeFilter; }
    public void setChangeFilter(String changeFilter) { this.changeFilter = changeFilter; }

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
