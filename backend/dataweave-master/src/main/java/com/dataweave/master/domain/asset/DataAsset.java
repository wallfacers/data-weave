package com.dataweave.master.domain.asset;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 数据资产编目条目（项目级）。data_table 之上的治理层，逻辑引用 (datasource_id, qualified_name)，
 * 不复制血缘。以 (tenant_id, project_id, datasource_id, qualified_name) 唯一去重（FR-010）。
 */
@Table("data_asset")
public class DataAsset {

    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    private Long datasourceId;
    private String qualifiedName;
    private String name;
    private String description;
    private Long ownerId;
    private Long stewardId;
    private String glossaryTerms;       // 业务术语 JSON 数组（存 VARCHAR）
    private String sensitivity;         // PUBLIC / INTERNAL / CONFIDENTIAL / PII
    private String schemaSnapshotJson;  // 列/类型快照（对账失效用）
    private String lineageTableRef;     // 喂 LineageQueryService 的 tableId
    private String status;              // ACTIVE / STALE / RETIRED
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public DataAsset() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }

    public String getQualifiedName() { return qualifiedName; }
    public void setQualifiedName(String qualifiedName) { this.qualifiedName = qualifiedName; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public Long getStewardId() { return stewardId; }
    public void setStewardId(Long stewardId) { this.stewardId = stewardId; }

    public String getGlossaryTerms() { return glossaryTerms; }
    public void setGlossaryTerms(String glossaryTerms) { this.glossaryTerms = glossaryTerms; }

    public String getSensitivity() { return sensitivity; }
    public void setSensitivity(String sensitivity) { this.sensitivity = sensitivity; }

    public String getSchemaSnapshotJson() { return schemaSnapshotJson; }
    public void setSchemaSnapshotJson(String schemaSnapshotJson) { this.schemaSnapshotJson = schemaSnapshotJson; }

    public String getLineageTableRef() { return lineageTableRef; }
    public void setLineageTableRef(String lineageTableRef) { this.lineageTableRef = lineageTableRef; }

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
