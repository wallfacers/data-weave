package com.dataweave.alert.application;

import com.dataweave.alert.domain.AlertRule;
import com.dataweave.alert.domain.repository.AlertRuleRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AlertRuleService {

    private final AlertRuleRepository repo;

    public AlertRuleService(AlertRuleRepository repo) { this.repo = repo; }

    public List<AlertRule> list(Long tenantId, String signalSource, Boolean enabled, int offset, int limit) {
        if (signalSource != null && enabled != null) {
            return repo.findByTenantIdAndSignalSourceAndEnabled(tenantId, signalSource, enabled ? 1 : 0);
        }
        return repo.findByTenantId(tenantId, offset, limit);
    }

    public AlertRule get(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new BizException("alert.rule_not_found", id));
    }

    public AlertRule create(AlertRule rule) {
        return repo.save(rule);
    }

    public AlertRule update(Long id, AlertRule patch) {
        AlertRule existing = get(id);
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        if (patch.getSignalSource() != null) existing.setSignalSource(patch.getSignalSource());
        if (patch.getEvalMode() != null) existing.setEvalMode(patch.getEvalMode());
        if (patch.getEvalIntervalSec() != null) existing.setEvalIntervalSec(patch.getEvalIntervalSec());
        if (patch.getConditionJson() != null) existing.setConditionJson(patch.getConditionJson());
        if (patch.getSeverity() != null) existing.setSeverity(patch.getSeverity());
        if (patch.getForDuration() != null) existing.setForDuration(patch.getForDuration());
        if (patch.getDedupKeyTemplate() != null) existing.setDedupKeyTemplate(patch.getDedupKeyTemplate());
        if (patch.getSuppressWindowSec() != null) existing.setSuppressWindowSec(patch.getSuppressWindowSec());
        if (patch.getAutoResolve() != null) existing.setAutoResolve(patch.getAutoResolve());
        if (patch.getLabelsJson() != null) existing.setLabelsJson(patch.getLabelsJson());
        existing.setUpdatedBy(patch.getUpdatedBy());
        return repo.save(existing);
    }

    public void delete(Long id) {
        get(id); // ensure exists
        repo.deleteById(id);
    }
}
