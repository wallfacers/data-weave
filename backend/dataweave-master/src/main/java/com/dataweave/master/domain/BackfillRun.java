package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 补数据批次（data-ops-center）：一次「按 任务/工作流 × 日期区间」补数操作的父记录。
 *
 * <p>进度（success/failed/running）不落库，查询时按 {@code task_instance.backfill_run_id} 聚合子实例状态
 * 推导（避免可变计数器一致性问题）。{@code state} 同理为聚合派生的展示态。
 */
@Table("backfill_run")
public class BackfillRun {

    @Id
    private UUID id;
    private Long tenantId;
    private Long projectId;
    private String targetType;        // task | workflow
    private Long targetId;
    private String targetName;
    private String dateStart;         // yyyy-MM-dd（含）
    private String dateEnd;           // yyyy-MM-dd（含）
    private Integer includeDownstream; // 0/1
    private Integer parallelism;
    private String state;             // RUNNING / SUCCESS / FAILED / PARTIAL
    private Integer total;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Long version;

    public BackfillRun() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }

    public String getDateStart() { return dateStart; }
    public void setDateStart(String dateStart) { this.dateStart = dateStart; }

    public String getDateEnd() { return dateEnd; }
    public void setDateEnd(String dateEnd) { this.dateEnd = dateEnd; }

    public Integer getIncludeDownstream() { return includeDownstream; }
    public void setIncludeDownstream(Integer includeDownstream) { this.includeDownstream = includeDownstream; }

    public Integer getParallelism() { return parallelism; }
    public void setParallelism(Integer parallelism) { this.parallelism = parallelism; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
