package com.dataweave.master.domain.asset;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 指标市场上架（项目级）。复用现有 atomic/derived_metrics 定义（不复制口径，D3）。
 * 以 (tenant_id, project_id, metric_type, metric_id) 唯一。
 */
@Table("metric_listing")
public class MetricListing {

    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    private String metricType;      // ATOMIC / DERIVED
    private Long metricId;          // 引用 atomic_metrics/derived_metrics.id
    private String metricCode;
    private Long ownerId;
    private String certification;   // NONE / CERTIFIED
    private Long certifiedBy;
    private LocalDateTime certifiedAt;
    private String freshnessInfo;
    private String description;
    private String status;          // LISTED / DELISTED
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public MetricListing() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getMetricType() { return metricType; }
    public void setMetricType(String metricType) { this.metricType = metricType; }

    public Long getMetricId() { return metricId; }
    public void setMetricId(Long metricId) { this.metricId = metricId; }

    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getCertification() { return certification; }
    public void setCertification(String certification) { this.certification = certification; }

    public Long getCertifiedBy() { return certifiedBy; }
    public void setCertifiedBy(Long certifiedBy) { this.certifiedBy = certifiedBy; }

    public LocalDateTime getCertifiedAt() { return certifiedAt; }
    public void setCertifiedAt(LocalDateTime certifiedAt) { this.certifiedAt = certifiedAt; }

    public String getFreshnessInfo() { return freshnessInfo; }
    public void setFreshnessInfo(String freshnessInfo) { this.freshnessInfo = freshnessInfo; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

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
