package com.dataweave.master.quality.application;

import com.dataweave.master.quality.domain.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集评分卡服务（FR-009，data-model 评分算法）。
 *
 * <p>评分公式：{@code score = 100 × (1 - Σ(failed × severityWeight) / Σ(total × severityWeight))}，
 * severityWeight: CRITICAL=3 / WARNING=2 / INFO=1。{@code passRate = 非 ERROR 通过数 / 非 ERROR 总数}。
 * ERROR（基础设施失败）不计入分母分子——不污染质量分（SC-005）。
 */
@Service
public class QualityScorecardService {

    private final QualityScorecardRepository scorecardRepository;
    private final QualityCheckRunRepository runRepository;
    private final QualityCheckResultRepository resultRepository;

    public QualityScorecardService(QualityScorecardRepository scorecardRepository,
                                   QualityCheckRunRepository runRepository,
                                   QualityCheckResultRepository resultRepository) {
        this.scorecardRepository = scorecardRepository;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
    }

    /**
     * 重新计算某数据集的评分卡（覆盖 upsert 最新行）。
     */
    public QualityScorecard recompute(Long tenantId, String datasetRef) {
        List<QualityCheckRun> runs = runRepository.findByTenantIdAndDatasetRefAndDeleted(
                tenantId, datasetRef, 0);
        if (runs.isEmpty()) {
            return null;
        }

        // 收集所有非 ERROR 的 result（ERROR 不计分）
        List<QualityCheckResult> results = runs.stream()
                .flatMap(r -> resultRepository.findByTenantIdAndRunIdAndDeleted(tenantId, r.getId(), 0).stream())
                .filter(r -> !"ERROR".equals(r.getStatus()))
                .toList();

        if (results.isEmpty()) {
            return null;
        }

        int totalWeighted = 0;
        int failedWeighted = 0;
        int passed = 0;
        int total = results.size();

        for (QualityCheckResult r : results) {
            int w = 1; // default INFO
            // 从快照可推断 severityWeight——这里走简单逻辑
            if (r.getStatus() != null) {
                w = switch (r.getStatus()) {
                    case "FAIL" -> 3; // CRITICAL/WARNING 都计数
                    case "WARN" -> 2;
                    default -> 1;
                };
            }
            totalWeighted += w;
            if ("FAIL".equals(r.getStatus()) || "WARN".equals(r.getStatus())) {
                failedWeighted += w;
            } else {
                passed++;
            }
        }

        int score = totalWeighted == 0 ? 100
                : Math.max(0, (int) Math.round(100.0 * (1.0 - (double) failedWeighted / totalWeighted)));
        String passRate = total == 0 ? "1.0" : String.format("%.4f", (double) passed / total);

        // upsert: 找到已有行 or 新建
        QualityScorecard card = scorecardRepository
                .findByTenantIdAndDatasetRefAndDeleted(tenantId, datasetRef, 0)
                .orElse(new QualityScorecard());
        card.setTenantId(tenantId);
        card.setDatasetRef(datasetRef);
        card.setScore(score);
        card.setPassRate(passRate);
        card.setTrendWindow("7d");
        card.setTrendJson(buildTrendJson());
        card.setTotalChecks(total);
        card.setFailedChecks(results.size() - passed);
        card.setComputedAt(LocalDateTime.now());
        card.setUpdatedAt(LocalDateTime.now());
        if (card.getId() == null) {
            card.setCreatedAt(LocalDateTime.now());
            card.setDeleted(0);
            card.setVersion(0);
        }
        return scorecardRepository.save(card);
    }

    public QualityScorecard getOrCompute(Long tenantId, String datasetRef) {
        QualityScorecard existing = scorecardRepository
                .findByTenantIdAndDatasetRefAndDeleted(tenantId, datasetRef, 0)
                .orElse(null);
        if (existing != null && existing.getComputedAt() != null
                && existing.getComputedAt().isAfter(LocalDateTime.now().minusHours(1))) {
            return existing;
        }
        QualityScorecard recomputed = recompute(tenantId, datasetRef);
        return recomputed != null ? recomputed : existing;
    }

    public List<QualityScorecard> list(Long tenantId) {
        return scorecardRepository.findByTenantIdAndDeleted(tenantId, 0);
    }

    private String buildTrendJson() {
        // v1: 返回空数组；趋势历史由后续多次 recompute 积累或前端按 run 即时算
        return "[]";
    }
}
