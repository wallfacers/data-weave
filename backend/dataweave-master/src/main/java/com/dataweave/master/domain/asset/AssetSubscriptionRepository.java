package com.dataweave.master.domain.asset;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface AssetSubscriptionRepository extends CrudRepository<AssetSubscription, Long> {

    Optional<AssetSubscription> findFirstByTenantIdAndSubscriberUserIdAndTargetTypeAndTargetIdAndDeleted(
            Long tenantId, Long subscriberUserId, String targetType, Long targetId, Integer deleted);

    Optional<AssetSubscription> findByIdAndTenantIdAndDeleted(Long id, Long tenantId, Integer deleted);

    /** 变更时反查订阅者（target 维度）。 */
    List<AssetSubscription> findByTenantIdAndTargetTypeAndTargetIdAndDeleted(
            Long tenantId, String targetType, Long targetId, Integer deleted);

    List<AssetSubscription> findByTenantIdAndSubscriberUserIdAndDeleted(
            Long tenantId, Long subscriberUserId, Integer deleted);
}
