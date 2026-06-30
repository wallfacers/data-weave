package com.dataweave.alert.domain.repository;

import com.dataweave.alert.domain.AlertEvent;
import java.util.List;
import java.util.Optional;

public interface AlertEventRepository {
    Optional<AlertEvent> findById(Long id);
    Optional<AlertEvent> findByTenantIdAndFingerprintAndState(Long tenantId, String fingerprint, String state);
    List<AlertEvent> findByTenantIdAndState(Long tenantId, String state, int offset, int limit);
    int countByTenantIdAndState(Long tenantId, String state);
    List<AlertEvent> findByTenantId(Long tenantId, int offset, int limit);
    int countByTenantId(Long tenantId);
    AlertEvent save(AlertEvent event);
    /** CAS update state only if current state matches expected */
    boolean casState(Long id, String expectedState, String newState);
    /** Update count and last_fired_at for dedup within suppress window */
    int incrementCount(Long id, Integer newCount);
    /** Mark resolved */
    int markResolved(Long id);
    /** Mark acknowledged */
    int markAcked(Long id, Long ackedBy);
    /** Mark suppressed */
    int markSuppressed(Long id);
}
