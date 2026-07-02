package com.dataweave.master.application.asset;

import com.dataweave.master.domain.asset.AssetSubscription;
import com.dataweave.master.domain.asset.AssetSubscriptionRepository;
import com.dataweave.master.domain.signal.AlertSignal;
import com.dataweave.master.i18n.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订阅 + 变更通知（FR-007 / SC-005）。约定变更经 {@link AlertSignal} ASSET_CHANGED 喂份1（021）。
 *
 * <p><b>接缝（seam.md）</b>：master 编译期不依赖 alert，用框架 {@link ApplicationEventPublisher} publish；
 * 021 侧 {@code @EventListener} 据订阅者所配规则/通道分发。退订后变更不再命中订阅者 → 不发信号（SC-005）。
 */
@Service
public class AssetSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AssetSubscriptionService.class);

    private final AssetSubscriptionRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final CatalogMetrics metrics;

    public AssetSubscriptionService(AssetSubscriptionRepository repository,
                                    ApplicationEventPublisher eventPublisher,
                                    CatalogMetrics metrics) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    /** 订阅（幂等：已存在则更新 change_filter）。target_type ∈ ASSET/METRIC。 */
    @Transactional
    public AssetSubscription subscribe(long tenantId, long userId, String targetType, Long targetId, String changeFilter) {
        if (targetId == null || targetType == null || targetType.isBlank()) {
            throw new BizException("catalog.subscription_invalid").withHttpStatus(400);
        }
        String tt = targetType.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        Optional<AssetSubscription> existing = repository
                .findFirstByTenantIdAndSubscriberUserIdAndTargetTypeAndTargetIdAndDeleted(tenantId, userId, tt, targetId, 0);
        AssetSubscription sub = existing.orElseGet(AssetSubscription::new);
        if (sub.getId() == null) {
            sub.setTenantId(tenantId);
            sub.setSubscriberUserId(userId);
            sub.setTargetType(tt);
            sub.setTargetId(targetId);
            sub.setCreatedBy(userId);
            sub.setCreatedAt(now);
            sub.setDeleted(0);
            sub.setVersion(0);
        }
        sub.setChangeFilter(changeFilter);
        sub.setUpdatedBy(userId);
        sub.setUpdatedAt(now);
        AssetSubscription saved = repository.save(sub);
        metrics.recordWrite("subscribe");
        return saved;
    }

    /** 退订（软删，校验属主 + 租户）。 */
    @Transactional
    public void unsubscribe(long tenantId, long userId, Long id) {
        AssetSubscription sub = repository.findByIdAndTenantIdAndDeleted(id, tenantId, 0)
                .orElseThrow(() -> new BizException("catalog.subscription_not_found").withHttpStatus(404));
        if (sub.getSubscriberUserId() == null || sub.getSubscriberUserId() != userId) {
            throw new BizException("catalog.subscription_forbidden").withHttpStatus(403);
        }
        sub.setDeleted(1);
        sub.setUpdatedBy(userId);
        sub.setUpdatedAt(LocalDateTime.now());
        repository.save(sub);
        metrics.recordWrite("unsubscribe");
    }

    public List<AssetSubscription> listForUser(long tenantId, long userId) {
        return repository.findByTenantIdAndSubscriberUserIdAndDeleted(tenantId, userId, 0);
    }

    /**
     * 变更通知：反查匹配订阅者，命中 → publish ASSET_CHANGED（喂 021）。
     *
     * @param changeType schema / quality / freshness（与订阅 change_filter 匹配才通知）
     * @return 是否发射了信号（无匹配订阅者 → false，即「退订后不通知」）
     */
    public boolean notifyChange(long tenantId, String targetType, Long targetId, String changeType, Map<String, Object> context) {
        String tt = targetType == null ? "" : targetType.trim().toUpperCase();
        List<AssetSubscription> subs = repository.findByTenantIdAndTargetTypeAndTargetIdAndDeleted(tenantId, tt, targetId, 0);
        Set<Long> matched = subs.stream()
                .filter(s -> filterMatches(s.getChangeFilter(), changeType))
                .map(AssetSubscription::getSubscriberUserId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (matched.isEmpty()) {
            return false;
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        if (context != null) ctx.putAll(context);
        ctx.put("targetType", tt);
        ctx.put("targetId", targetId);
        ctx.put("changeType", changeType);
        ctx.put("subscriberUserIds", List.copyOf(matched));
        AlertSignal signal = new AlertSignal(AlertSignal.Type.ASSET_CHANGED, tenantId,
                tt + ":" + targetId, "INFO", ctx);
        eventPublisher.publishEvent(signal);
        metrics.recordAssetChanged();
        log.debug("ASSET_CHANGED published: tenant={}, {}:{}, changeType={}, subscribers={}",
                tenantId, tt, targetId, changeType, matched.size());
        return true;
    }

    /** change_filter 为空/缺省 = 订阅全部变更；否则 CSV 命中 changeType 才通知。 */
    private boolean filterMatches(String changeFilter, String changeType) {
        if (changeFilter == null || changeFilter.isBlank()) return true;
        if (changeType == null || changeType.isBlank()) return true;
        for (String f : changeFilter.split(",")) {
            if (f.trim().equalsIgnoreCase(changeType.trim())) return true;
        }
        return false;
    }
}
