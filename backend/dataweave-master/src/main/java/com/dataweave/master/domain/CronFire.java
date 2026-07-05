package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * cron 触发防重护栏（design D4）。复合唯一键 (workflow_id, scheduled_fire_time)：
 * 每个工作流的每个触发点仅允许一行，撞键即放弃本次触发 —— 多 master 零协调防重。
 */
@Table("cron_fire")
public class CronFire {

    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    private Long workflowId;
    private LocalDateTime scheduledFireTime;
    private UUID workflowInstanceId;
    private LocalDateTime firedAt;
    private LocalDateTime createdAt;
    private String status = "PENDING";  // 045 触发点生命周期：PENDING(fireArm INSERT)/FIRED(fireExecute 回填)/DEAD(reconciler 超时放弃)

    public CronFire() {
    }

    public CronFire(Long workflowId, LocalDateTime scheduledFireTime) {
        this.workflowId = workflowId;
        this.scheduledFireTime = scheduledFireTime;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public LocalDateTime getScheduledFireTime() {
        return scheduledFireTime;
    }

    public void setScheduledFireTime(LocalDateTime scheduledFireTime) {
        this.scheduledFireTime = scheduledFireTime;
    }

    public UUID getWorkflowInstanceId() {
        return workflowInstanceId;
    }

    public void setWorkflowInstanceId(UUID workflowInstanceId) {
        this.workflowInstanceId = workflowInstanceId;
    }

    public LocalDateTime getFiredAt() {
        return firedAt;
    }

    public void setFiredAt(LocalDateTime firedAt) {
        this.firedAt = firedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
