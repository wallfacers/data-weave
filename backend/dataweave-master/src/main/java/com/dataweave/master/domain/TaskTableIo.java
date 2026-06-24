package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

/**
 * 设计态血缘边：任务读/写了哪些表（建任务即写入）。
 * direction：READ/WRITE；source：AGENT/SQL_PARSED/FORM；confidence：CONFIRMED/UNVERIFIED/CONFLICT。
 */
@Table("task_table_io")
public class TaskTableIo {
    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    private Long taskDefId;
    private Integer taskVersionNo;
    private Long tableId;
    private String direction;
    private String source;
    private String confidence;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public TaskTableIo() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getTaskDefId() { return taskDefId; }
    public void setTaskDefId(Long taskDefId) { this.taskDefId = taskDefId; }

    public Integer getTaskVersionNo() { return taskVersionNo; }
    public void setTaskVersionNo(Integer taskVersionNo) { this.taskVersionNo = taskVersionNo; }

    public Long getTableId() { return tableId; }
    public void setTableId(Long tableId) { this.tableId = tableId; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }

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
