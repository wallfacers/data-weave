package com.dataweave.alert.domain.repository;

import com.dataweave.alert.domain.AlertSilence;
import java.util.List;
import java.util.Optional;

public interface AlertSilenceRepository {
    Optional<AlertSilence> findById(Long id);
    List<AlertSilence> findActiveByTenantId(Long tenantId);
    AlertSilence save(AlertSilence silence);
    int deleteById(Long id);
}
