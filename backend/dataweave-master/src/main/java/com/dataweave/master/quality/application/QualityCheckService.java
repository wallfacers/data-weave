package com.dataweave.master.quality.application;

import com.dataweave.master.quality.domain.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 质量运行/结果查询服务（FR-004）。
 */
@Service
public class QualityCheckService {

    private final QualityCheckRunRepository runRepository;
    private final QualityCheckResultRepository resultRepository;

    public QualityCheckService(QualityCheckRunRepository runRepository,
                                QualityCheckResultRepository resultRepository) {
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
    }

    public List<QualityCheckRun> listRuns(Long tenantId) {
        return runRepository.findByTenantIdAndDeleted(tenantId, 0);
    }

    public Optional<QualityCheckRun> getRun(Long id, Long tenantId) {
        return runRepository.findByIdAndTenantIdAndDeleted(id, tenantId, 0);
    }

    public List<QualityCheckResult> getResults(Long runId, Long tenantId) {
        return resultRepository.findByTenantIdAndRunIdAndDeleted(tenantId, runId, 0);
    }

    public Optional<QualityCheckResult> getResult(Long id, Long tenantId) {
        return resultRepository.findByIdAndTenantIdAndDeleted(id, tenantId, 0);
    }

    public List<QualityCheckRun> getRunsByTaskInstance(Long tenantId, UUID taskInstanceId) {
        return runRepository.findByTenantIdAndTaskInstanceIdAndDeleted(tenantId, taskInstanceId, 0);
    }

    public List<QualityCheckRun> getRunsByDataset(Long tenantId, String datasetRef) {
        return runRepository.findByTenantIdAndDatasetRefAndDeleted(tenantId, datasetRef, 0);
    }
}
