package com.dataweave.master.domain.asset;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 指标复用引用（项目级，防环）。建引用前做有向可达性检查（consumer 已（间接）被 listing 复用则拒）。
 */
@Table("metric_reuse_ref")
public class MetricReuseRef {

    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    private Long listingId;          // 被复用的上架指标
    private String consumerType;     // METRIC / TASK / ASSET
    private String consumerRef;      // 复用方标识
    private Long createdBy;
    private LocalDateTime createdAt;
    private Integer deleted;
    private Integer version;

    public MetricReuseRef() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getListingId() { return listingId; }
    public void setListingId(Long listingId) { this.listingId = listingId; }

    public String getConsumerType() { return consumerType; }
    public void setConsumerType(String consumerType) { this.consumerType = consumerType; }

    public String getConsumerRef() { return consumerRef; }
    public void setConsumerRef(String consumerRef) { this.consumerRef = consumerRef; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
