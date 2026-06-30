package com.dataweave.master.quality.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 数据集评分卡（quality_scorecard，FR-009）。
 *
 * <p>{@code score}=0-100（通过率 + 加权 severity，{@link Severity#weight()}）；{@code passRate} 字符串存避浮点方言差异；
 * {@code trendJson} 时间序列点供前端趋势图。{@code (tenantId, datasetRef)} 唯一（每数据集一行最新评分）。
 * ERROR 不计入 {@code totalChecks}/{@code failedChecks}（基础设施失败不污染质量分，data-model 评分算法）。
 *
 * <p>供 023 资产质量徽章复用（spec 范围边界）。
 */
@Table("quality_scorecard")
public class QualityScorecard {

    @Id
    private Long id;
    private Long tenantId;
    private String datasetRef;
    private Integer score;
    private String passRate;
    private String trendWindow;
    private String trendJson;
    private Integer totalChecks;
    private Integer failedChecks;
    private LocalDateTime computedAt;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public QualityScorecard() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getDatasetRef() { return datasetRef; }
    public void setDatasetRef(String datasetRef) { this.datasetRef = datasetRef; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getPassRate() { return passRate; }
    public void setPassRate(String passRate) { this.passRate = passRate; }
    public String getTrendWindow() { return trendWindow; }
    public void setTrendWindow(String trendWindow) { this.trendWindow = trendWindow; }
    public String getTrendJson() { return trendJson; }
    public void setTrendJson(String trendJson) { this.trendJson = trendJson; }
    public Integer getTotalChecks() { return totalChecks; }
    public void setTotalChecks(Integer totalChecks) { this.totalChecks = totalChecks; }
    public Integer getFailedChecks() { return failedChecks; }
    public void setFailedChecks(Integer failedChecks) { this.failedChecks = failedChecks; }
    public LocalDateTime getComputedAt() { return computedAt; }
    public void setComputedAt(LocalDateTime computedAt) { this.computedAt = computedAt; }
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
