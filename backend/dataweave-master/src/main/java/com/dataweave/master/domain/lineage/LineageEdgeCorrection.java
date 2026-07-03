package com.dataweave.master.domain.lineage;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 041 人工修正裁决（当前生效态；审计明细在 agent_action）。
 * 语义键 = (tenant, project, task, direction, tableKey[, columnKey])，与脚本写法解耦（FR-007/008）。
 * status: CONFIRMED（确认，置信度升级）/ REMOVED（剔除，抑制入图）；撤销 = 删行。
 */
@Table("lineage_edge_correction")
public class LineageEdgeCorrection {

    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    private Long taskDefId;
    private String direction;   // READ | WRITE
    private String tableKey;    // dsKey|norm(table)（与 neo4j tableKey 同构）
    private String columnKey;   // ''=表级
    private String status;      // CONFIRMED | REMOVED
    private String operator;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public LineageEdgeCorrection() {
    }

    public LineageEdgeCorrection(Long tenantId, Long projectId, Long taskDefId,
                                 String direction, String tableKey, String columnKey,
                                 String status, String operator) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.taskDefId = taskDefId;
        this.direction = direction;
        this.tableKey = tableKey;
        this.columnKey = columnKey == null ? "" : columnKey;
        this.status = status;
        this.operator = operator;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
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

    public Long getTaskDefId() {
        return taskDefId;
    }

    public void setTaskDefId(Long taskDefId) {
        this.taskDefId = taskDefId;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getTableKey() {
        return tableKey;
    }

    public void setTableKey(String tableKey) {
        this.tableKey = tableKey;
    }

    public String getColumnKey() {
        return columnKey;
    }

    public void setColumnKey(String columnKey) {
        this.columnKey = columnKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
