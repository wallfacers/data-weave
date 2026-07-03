package com.dataweave.master.domain.lineage;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 041 未解析提示：脚本中静态无法确定读写目标的疑似点（FR-006 宁缺毋滥留痕）。
 * 每次抽取按 (tenant, project, task) 整体替换（与 recordTaskIo replace-per-task 一致）。
 */
@Table("lineage_unresolved_hint")
public class LineageUnresolvedHint {

    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    private Long taskDefId;
    private Integer versionNo;
    private String kind;        // DYNAMIC_TABLE / DYNAMIC_SQL / TIMEOUT / PARSE_FAIL
    private String scriptHint;  // 行号 + 截断片段
    private LocalDateTime createdAt;

    public LineageUnresolvedHint() {
    }

    public LineageUnresolvedHint(Long tenantId, Long projectId, Long taskDefId,
                                 Integer versionNo, String kind, String scriptHint) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.taskDefId = taskDefId;
        this.versionNo = versionNo;
        this.kind = kind;
        this.scriptHint = scriptHint;
        this.createdAt = LocalDateTime.now();
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

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getScriptHint() {
        return scriptHint;
    }

    public void setScriptHint(String scriptHint) {
        this.scriptHint = scriptHint;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
