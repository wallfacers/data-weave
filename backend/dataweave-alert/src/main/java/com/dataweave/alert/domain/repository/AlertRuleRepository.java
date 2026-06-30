package com.dataweave.alert.domain.repository;

import com.dataweave.alert.domain.AlertRule;
import java.util.List;
import java.util.Optional;

public interface AlertRuleRepository {
    Optional<AlertRule> findById(Long id);
    List<AlertRule> findByTenantIdAndSignalSourceAndEnabled(Long tenantId, String signalSource, Integer enabled);
    List<AlertRule> findByTenantIdAndEvalModeAndEnabled(Long tenantId, String evalMode, Integer enabled);
    List<AlertRule> findByTenantId(Long tenantId, int offset, int limit);
    int countByTenantId(Long tenantId);
    AlertRule save(AlertRule rule);
    int deleteById(Long id);
}
