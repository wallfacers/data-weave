package com.dataweave.master.quality.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 单断言结果（quality_check_result，FR-004）。
 *
 * <p>{@code status}（{@link CheckStatus}）+ {@code measuredValue}（实测标量）+ {@code expected}（期望）+
 * {@code failedSampleRef}（失败样本<b>引用</b>，受租户+权限控制，不无差别明文落库，FR-016）。
 * {@code signalEmitted} 幂等防 {@code QUALITY_FAILED} 重发（SC-004）。
 *
 * <p>{@code ERROR}=probe SKIPPED（基础设施失败）→ 不发信号/不阻断/不计入质量分（SC-005）。
 */
@Table("quality_check_result")
public class QualityCheckResult {

    @Id
    private Long id;
    private Long tenantId;
    private Long runId;
    private Long ruleId;
    private String assertionType;
    private String status;
    private String measuredValue;
    private String expected;
    private Integer sampled;
    private String failedSampleRef;
    private String message;
    private Integer signalEmitted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public QualityCheckResult() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getAssertionType() { return assertionType; }
    public void setAssertionType(String assertionType) { this.assertionType = assertionType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMeasuredValue() { return measuredValue; }
    public void setMeasuredValue(String measuredValue) { this.measuredValue = measuredValue; }
    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }
    public Integer getSampled() { return sampled; }
    public void setSampled(Integer sampled) { this.sampled = sampled; }
    public String getFailedSampleRef() { return failedSampleRef; }
    public void setFailedSampleRef(String failedSampleRef) { this.failedSampleRef = failedSampleRef; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Integer getSignalEmitted() { return signalEmitted; }
    public void setSignalEmitted(Integer signalEmitted) { this.signalEmitted = signalEmitted; }
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
