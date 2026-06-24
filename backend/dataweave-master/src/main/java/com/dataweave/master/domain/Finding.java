package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 通用主动发现模型（proactive-discovery 的「窄腰」）。
 *
 * <p>任意 {@code Inspector}（失败/数据质量/SLA/血缘…）产出同构 Finding，下游举手台、主动播报、
 * 闸门修复全部围绕它，接新巡检器不碰下游。{@code TASK_FAILURE} 来源由 TaskFailureInspector 把现有
 * {@code TaskDiagnosis} 映射而来（复用诊断，不重写），{@link #taskDiagnosisId} 关联回诊断行。
 */
@Table("finding")
public class Finding {

    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    /** 发现来源：TASK_FAILURE / DATA_QUALITY / SLA ... */
    private String source;
    /** 严重度：INFO / WARN / CRITICAL */
    private String severity;
    /** 被发现对象类型：TASK_INSTANCE / TABLE / WORKFLOW ... */
    private String targetType;
    /** 被发现对象标识（实例 UUID、表名等，统一存字符串）。 */
    private String targetId;
    private String title;
    private String rootCause;
    /** 证据：{nodeMem,nodeCpu,nodeLoad,concurrentTasks,history,...} 的 JSON 文本。 */
    private String evidenceJson;
    /** 可执行修复项：[{key,label,actionType}] 的 JSON 文本。 */
    private String actionsJson;
    /** OPEN / ANNOUNCED / RESOLVED */
    private String status;
    /** 主动播报去重标记（0=未播报，1=已播报）。 */
    private Integer announced;
    /** TASK_FAILURE 来源关联的诊断行 id。 */
    private Long taskDiagnosisId;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public Finding() {
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(String evidenceJson) {
        this.evidenceJson = evidenceJson;
    }

    public String getActionsJson() {
        return actionsJson;
    }

    public void setActionsJson(String actionsJson) {
        this.actionsJson = actionsJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAnnounced() {
        return announced;
    }

    public void setAnnounced(Integer announced) {
        this.announced = announced;
    }

    public Long getTaskDiagnosisId() {
        return taskDiagnosisId;
    }

    public void setTaskDiagnosisId(Long taskDiagnosisId) {
        this.taskDiagnosisId = taskDiagnosisId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
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

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
