package com.dataweave.master.quality.application;

import com.dataweave.master.quality.domain.QualityRule;
import com.dataweave.master.quality.domain.QualityRuleRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 断言 CRUD 服务（FR-001）。断言写经 {@code PolicyEngine} 闸门（D5），UI CRUD 走普通鉴权 + 审计。
 */
@Service
public class QualityRuleService {

    private final QualityRuleRepository repository;

    public QualityRuleService(QualityRuleRepository repository) {
        this.repository = repository;
    }

    public List<QualityRule> list(Long tenantId) {
        return repository.findByTenantIdAndDeleted(tenantId, 0);
    }

    public Optional<QualityRule> get(Long id, Long tenantId) {
        return repository.findByIdAndTenantIdAndDeleted(id, tenantId, 0);
    }

    public QualityRule create(QualityRule rule) {
        rule.setId(null);
        rule.setEnabled(1);
        rule.setDeleted(0);
        rule.setVersion(0);
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        return repository.save(rule);
    }

    public QualityRule update(QualityRule existing, QualityRule patch) {
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getDatasetRef() != null) existing.setDatasetRef(patch.getDatasetRef());
        if (patch.getDatasourceId() != null) existing.setDatasourceId(patch.getDatasourceId());
        if (patch.getAssertionType() != null) existing.setAssertionType(patch.getAssertionType());
        if (patch.getExpectationJson() != null) existing.setExpectationJson(patch.getExpectationJson());
        if (patch.getSeverity() != null) existing.setSeverity(patch.getSeverity());
        if (patch.getAction() != null) existing.setAction(patch.getAction());
        if (patch.getSamplingJson() != null) existing.setSamplingJson(patch.getSamplingJson());
        if (patch.getBoundTaskId() != null) existing.setBoundTaskId(patch.getBoundTaskId());
        if (patch.getScheduleCron() != null) existing.setScheduleCron(patch.getScheduleCron());
        existing.setUpdatedAt(LocalDateTime.now());
        return repository.save(existing);
    }

    public void delete(Long id, Long tenantId) {
        repository.findByIdAndTenantIdAndDeleted(id, tenantId, 0).ifPresent(r -> {
            r.setDeleted(1);
            r.setUpdatedAt(LocalDateTime.now());
            repository.save(r);
        });
    }

    public List<QualityRule> findByDataset(Long tenantId, String datasetRef) {
        return repository.findByTenantIdAndDatasetRefAndDeleted(tenantId, datasetRef, 0);
    }

    public List<QualityRule> findScheduled(Long tenantId) {
        return repository.findScheduledRules(tenantId);
    }
}
