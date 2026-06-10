package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Table("workflow_def")
public class WorkflowDef {
    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    private String name;
    private String description;
    private String scheduleType;
    private String cron;
    private LocalDateTime scheduleStart;
    private LocalDateTime scheduleEnd;
    private String status;
    private Integer currentVersionNo;
    private Integer hasDraftChange;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Long version;

    public WorkflowDef() {}

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

    public String getScheduleType() { return scheduleType; }
    public void setScheduleType(String scheduleType) { this.scheduleType = scheduleType; }

    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }

    public LocalDateTime getScheduleStart() { return scheduleStart; }
    public void setScheduleStart(LocalDateTime scheduleStart) { this.scheduleStart = scheduleStart; }

    public LocalDateTime getScheduleEnd() { return scheduleEnd; }
    public void setScheduleEnd(LocalDateTime scheduleEnd) { this.scheduleEnd = scheduleEnd; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getCurrentVersionNo() { return currentVersionNo; }
    public void setCurrentVersionNo(Integer currentVersionNo) { this.currentVersionNo = currentVersionNo; }

    public Integer getHasDraftChange() { return hasDraftChange; }
    public void setHasDraftChange(Integer hasDraftChange) { this.hasDraftChange = hasDraftChange; }

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
