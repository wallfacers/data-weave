package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Table("task_def")
public class TaskDef {
    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    private String name;
    private String type;
    private String content;
    private Long datasourceId;
    private Long targetDatasourceId;
    private String paramsJson;
    private Integer timeoutSec;
    private Integer retryMax;
    private String status;
    private Integer currentVersionNo;
    private Integer hasDraftChange;
    private Integer priority;
    private Integer frozen;
    private String description;
    private Long ownerId;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Long version;
    private Long catalogNodeId;
    // 060 外部托管长驻作业标记（Flink 流式=true）；062 接通创作→下发链路后经产品路径可写。
    // 决定实例物化 long_running 快照 + 下发 detached 长驻分支 + timeout/自我中止豁免。
    private Boolean longRunning;
    // 067 声明式资源 {"memoryMb":4096,"cpuCores":2}；NULL=引擎默认
    private String resourcesJson;

    public TaskDef() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }

    public Long getTargetDatasourceId() { return targetDatasourceId; }
    public void setTargetDatasourceId(Long targetDatasourceId) { this.targetDatasourceId = targetDatasourceId; }

    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }

    public Integer getTimeoutSec() { return timeoutSec; }
    public void setTimeoutSec(Integer timeoutSec) { this.timeoutSec = timeoutSec; }

    public Integer getRetryMax() { return retryMax; }
    public void setRetryMax(Integer retryMax) { this.retryMax = retryMax; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getCurrentVersionNo() { return currentVersionNo; }
    public void setCurrentVersionNo(Integer currentVersionNo) { this.currentVersionNo = currentVersionNo; }

    public Integer getHasDraftChange() { return hasDraftChange; }
    public void setHasDraftChange(Integer hasDraftChange) { this.hasDraftChange = hasDraftChange; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Integer getFrozen() { return frozen; }
    public void setFrozen(Integer frozen) { this.frozen = frozen; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

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

    public Long getCatalogNodeId() { return catalogNodeId; }
    public void setCatalogNodeId(Long catalogNodeId) { this.catalogNodeId = catalogNodeId; }

    /** 060/062 外部托管长驻作业标记（Flink 流式=true）。null≡false（老数据/未设）。 */
    public Boolean getLongRunning() { return longRunning; }
    public void setLongRunning(Boolean longRunning) { this.longRunning = longRunning; }

    public String getResourcesJson() { return resourcesJson; }
    public void setResourcesJson(String resourcesJson) { this.resourcesJson = resourcesJson; }
}
