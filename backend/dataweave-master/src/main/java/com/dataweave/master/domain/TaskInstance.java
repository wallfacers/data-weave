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
    private String taskDefName;
    private String workflowDefName;
    private String cronExpression;
    private String contentOverride;
    private String paramsOverride;
    private String typeOverride;
    private String taskType;
    private String runMode;
    private UUID backfillRunId;
    private Integer backfillHeld;
    private String env;
    private String bizDate;
    private String state;
    private Integer attempt;
    // 060 计数双拆：attempt=纯下发纪元栅栏（casDispatch +1，isCurrentDispatch/worker 幂等键用）；
    // business_attempt=业务重试计数（仅曾进入 RUNNING 后失败才 +1，与 retry_max 比较）；infra 回收只动 infra_redispatch_count。
    private Integer businessAttempt;
    private Integer infraRedispatchCount;
    private String workerNodeCode;
    private LocalDateTime leaseExpireAt;
    private String externalJobHandle;   // 060 外部托管长驻作业句柄（JobID+REST JSON）；reattach 与实时卡片挂载点
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

    public String getTaskDefName() { return taskDefName; }
    public void setTaskDefName(String taskDefName) { this.taskDefName = taskDefName; }

    public String getWorkflowDefName() { return workflowDefName; }
    public void setWorkflowDefName(String workflowDefName) { this.workflowDefName = workflowDefName; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public String getContentOverride() { return contentOverride; }
    public void setContentOverride(String contentOverride) { this.contentOverride = contentOverride; }

    public String getParamsOverride() { return paramsOverride; }
    public void setParamsOverride(String paramsOverride) { this.paramsOverride = paramsOverride; }

    public String getTypeOverride() { return typeOverride; }
    public void setTypeOverride(String typeOverride) { this.typeOverride = typeOverride; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getRunMode() { return runMode; }
    public void setRunMode(String runMode) { this.runMode = runMode; }

    public UUID getBackfillRunId() { return backfillRunId; }
    public void setBackfillRunId(UUID backfillRunId) { this.backfillRunId = backfillRunId; }

    public Integer getBackfillHeld() { return backfillHeld; }
    public void setBackfillHeld(Integer backfillHeld) { this.backfillHeld = backfillHeld; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getBizDate() { return bizDate; }
    public void setBizDate(String bizDate) { this.bizDate = bizDate; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }

    /** 060 业务重试计数（仅曾进入 RUNNING 后失败才 +1；与 retry_max 比较）。 */
    public Integer getBusinessAttempt() { return businessAttempt; }
    public void setBusinessAttempt(Integer businessAttempt) { this.businessAttempt = businessAttempt; }

    /** 060 单实例连续 infra 重派计数（超 infra-redispatch-max → SUSPENDED）。 */
    public Integer getInfraRedispatchCount() { return infraRedispatchCount; }
    public void setInfraRedispatchCount(Integer infraRedispatchCount) { this.infraRedispatchCount = infraRedispatchCount; }

    public String getWorkerNodeCode() { return workerNodeCode; }
    public void setWorkerNodeCode(String workerNodeCode) { this.workerNodeCode = workerNodeCode; }

    public LocalDateTime getLeaseExpireAt() { return leaseExpireAt; }
    public void setLeaseExpireAt(LocalDateTime leaseExpireAt) { this.leaseExpireAt = leaseExpireAt; }

    /** 060 外部托管长驻作业句柄（JobID+REST JSON）。 */
    public String getExternalJobHandle() { return externalJobHandle; }
    public void setExternalJobHandle(String externalJobHandle) { this.externalJobHandle = externalJobHandle; }

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
