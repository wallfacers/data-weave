package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Table("workflow_dependency")
public class WorkflowDependency {
    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    private Long workflowId;
    private Long nodeId;
    private Long dependWorkflowId;
    private Long dependNodeId;
    private Integer dateOffset;
    private String depType;
    private Integer enabled;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Long version;

    public WorkflowDependency() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }

    public Long getDependWorkflowId() { return dependWorkflowId; }
    public void setDependWorkflowId(Long dependWorkflowId) { this.dependWorkflowId = dependWorkflowId; }

    public Long getDependNodeId() { return dependNodeId; }
    public void setDependNodeId(Long dependNodeId) { this.dependNodeId = dependNodeId; }

    public Integer getDateOffset() { return dateOffset; }
    public void setDateOffset(Integer dateOffset) { this.dateOffset = dateOffset; }

    public String getDepType() { return depType; }
    public void setDepType(String depType) { this.depType = depType; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

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

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
