package com.dataweave.alert.domain.repository;

import com.dataweave.alert.domain.EventSubscription;

import java.util.List;
import java.util.Optional;

public interface EventSubscriptionRepository {
    EventSubscription save(EventSubscription s);
    Optional<EventSubscription> findById(Long id);
    List<EventSubscription> findByTenantId(long tenantId);
    /** 某租户启用的全部订阅（事件匹配时遍历）。 */
    List<EventSubscription> findEnabledByTenantId(long tenantId);
    int deleteById(Long id);
}
