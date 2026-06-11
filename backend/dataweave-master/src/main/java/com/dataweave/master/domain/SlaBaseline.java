package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * SLA 基线实体（design D14，task 5.3）。
 *
 * <p>按 workflow + biz_date 记录数据就绪时刻，维护历史基线。
 * 统计仅含 NORMAL 实例（TEST 排除）。
 */
@Table("sla_baseline")
public class SlaBaseline {

    @Id
    private Long id;
    private Long workflowId;
    private String bizDate;
    private UUID workflowInstanceId;
    private LocalDateTime readyAt;
    private LocalDateTime baselineReadyAt;
    private Integer breached;
    private Integer breachMinutes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public SlaBaseline() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }

    public String getBizDate() { return bizDate; }
    public void setBizDate(String bizDate) { this.bizDate = bizDate; }

    public UUID getWorkflowInstanceId() { return workflowInstanceId; }
    public void setWorkflowInstanceId(UUID workflowInstanceId) { this.workflowInstanceId = workflowInstanceId; }

    public LocalDateTime getReadyAt() { return readyAt; }
    public void setReadyAt(LocalDateTime readyAt) { this.readyAt = readyAt; }

    public LocalDateTime getBaselineReadyAt() { return baselineReadyAt; }
    public void setBaselineReadyAt(LocalDateTime baselineReadyAt) { this.baselineReadyAt = baselineReadyAt; }

    public Integer getBreached() { return breached; }
    public void setBreached(Integer breached) { this.breached = breached; }

    public Integer getBreachMinutes() { return breachMinutes; }
    public void setBreachMinutes(Integer breachMinutes) { this.breachMinutes = breachMinutes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
