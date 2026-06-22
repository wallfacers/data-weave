package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Table("task_instance")
public class TaskInstance {
    @Id
    private UUID id;
    private Long tenantId;
    private Long projectId;
    private UUID workflowInstanceId;
    private Long workflowNodeId;
    private Long taskId;
    private Integer taskVersionNo;
    private String contentOverride;
    private String paramsOverride;
    private String typeOverride;
    private String runMode;
    private String bizDate;
    private String state;
    private Integer attempt;
    private String workerNodeCode;
    private LocalDateTime leaseExpireAt;
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String log;
    private Integer exitCode;
    private String errorMessage;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private String locale;
    private Long version;

    public TaskInstance() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public UUID getWorkflowInstanceId() { return workflowInstanceId; }
    public void setWorkflowInstanceId(UUID workflowInstanceId) { this.workflowInstanceId = workflowInstanceId; }

    public Long getWorkflowNodeId() { return workflowNodeId; }
    public void setWorkflowNodeId(Long workflowNodeId) { this.workflowNodeId = workflowNodeId; }

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }

    public Integer getTaskVersionNo() { return taskVersionNo; }
    public void setTaskVersionNo(Integer taskVersionNo) { this.taskVersionNo = taskVersionNo; }

    public String getContentOverride() { return contentOverride; }
    public void setContentOverride(String contentOverride) { this.contentOverride = contentOverride; }

    public String getParamsOverride() { return paramsOverride; }
    public void setParamsOverride(String paramsOverride) { this.paramsOverride = paramsOverride; }

    public String getTypeOverride() { return typeOverride; }
    public void setTypeOverride(String typeOverride) { this.typeOverride = typeOverride; }

    public String getRunMode() { return runMode; }
    public void setRunMode(String runMode) { this.runMode = runMode; }

    public String getBizDate() { return bizDate; }
    public void setBizDate(String bizDate) { this.bizDate = bizDate; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }

    public String getWorkerNodeCode() { return workerNodeCode; }
    public void setWorkerNodeCode(String workerNodeCode) { this.workerNodeCode = workerNodeCode; }

    public LocalDateTime getLeaseExpireAt() { return leaseExpireAt; }
    public void setLeaseExpireAt(LocalDateTime leaseExpireAt) { this.leaseExpireAt = leaseExpireAt; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    public String getLog() { return log; }
    public void setLog(String log) { this.log = log; }

    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

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

    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
