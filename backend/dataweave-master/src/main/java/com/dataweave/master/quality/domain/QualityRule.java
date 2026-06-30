package com.dataweave.master.quality.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 质量断言定义（quality_rule，FR-001）。
 *
 * <p>绑定数据集（{@code datasetRef} = {@code datasourceId:schema.table}）+ 期望参数（{@code expectationJson}，
 * 结构按 {@link AssertionType}）+ 严重度（{@link Severity}）+ 动作（{@link RuleAction}：BLOCK 阻断下游 / WARN 仅告警）。
 * 可绑 POST_TASK 门禁（{@code boundTaskId}）或独立调度（{@code scheduleCron}）。
 *
 * <p>{@code expectationJson}/{@code samplingJson} 为 String（VARCHAR 存 JSON，保 H2 兼容），
 * 序列化由 application 层 ObjectMapper（Jackson 3 {@code tools.jackson.databind}）处理。
 * 状态/类型列用 String（不挂 enum），与全域实体范式一致。
 */
@Table("quality_rule")
public class QualityRule {

    @Id
    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private String datasetRef;
    private Long datasourceId;
    private String assertionType;
    private String expectationJson;
    private String severity;
    private String action;
    private String samplingJson;
    private Long boundTaskId;
    private String scheduleCron;
    private Integer enabled;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public QualityRule() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDatasetRef() { return datasetRef; }
    public void setDatasetRef(String datasetRef) { this.datasetRef = datasetRef; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getAssertionType() { return assertionType; }
    public void setAssertionType(String assertionType) { this.assertionType = assertionType; }
    public String getExpectationJson() { return expectationJson; }
    public void setExpectationJson(String expectationJson) { this.expectationJson = expectationJson; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getSamplingJson() { return samplingJson; }
    public void setSamplingJson(String samplingJson) { this.samplingJson = samplingJson; }
    public Long getBoundTaskId() { return boundTaskId; }
    public void setBoundTaskId(Long boundTaskId) { this.boundTaskId = boundTaskId; }
    public String getScheduleCron() { return scheduleCron; }
    public void setScheduleCron(String scheduleCron) { this.scheduleCron = scheduleCron; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
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
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
