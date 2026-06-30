package com.dataweave.master.application.asset;

import com.dataweave.master.domain.asset.AssetSubscription;
import com.dataweave.master.domain.asset.AssetSubscriptionRepository;
import com.dataweave.master.domain.signal.AlertSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订阅变更通知单元测试（SC-005）：有匹配订阅者 → publish ASSET_CHANGED；无匹配/退订 → 不发。
 */
@ExtendWith(MockitoExtension.class)
class AssetSubscriptionServiceTest {

    @Mock AssetSubscriptionRepository repository;
    @Mock ApplicationEventPublisher publisher;
    @Mock CatalogMetrics metrics;

    private AssetSubscriptionService service() {
        return new AssetSubscriptionService(repository, publisher, metrics);
    }

    private AssetSubscription sub(long userId, String filter) {
        AssetSubscription s = new AssetSubscription();
        s.setSubscriberUserId(userId);
        s.setTargetType("ASSET");
        s.setTargetId(1L);
        s.setChangeFilter(filter);
        return s;
    }

    @Test
    void matchingSubscriber_publishesAssetChanged() {
        when(repository.findByTenantIdAndTargetTypeAndTargetIdAndDeleted(1L, "ASSET", 1L, 0))
                .thenReturn(List.of(sub(7L, "schema,quality")));
        boolean published = service().notifyChange(1L, "ASSET", 1L, "schema", Map.of("assetName", "dwd_order"));

        assertThat(published).isTrue();
        ArgumentCaptor<AlertSignal> captor = ArgumentCaptor.forClass(AlertSignal.class);
        verify(publisher).publishEvent(captor.capture());
        AlertSignal sig = captor.getValue();
        assertThat(sig.getType()).isEqualTo(AlertSignal.Type.ASSET_CHANGED);
        assertThat(sig.getTenantId()).isEqualTo(1L);
        assertThat(sig.getContext()).containsEntry("changeType", "schema");
        assertThat(sig.getContext().get("subscriberUserIds").toString()).contains("7");
    }

    @Test
    void noSubscriber_doesNotPublish() {
        when(repository.findByTenantIdAndTargetTypeAndTargetIdAndDeleted(1L, "ASSET", 1L, 0))
                .thenReturn(List.of());
        boolean published = service().notifyChange(1L, "ASSET", 1L, "schema", Map.of());
        assertThat(published).isFalse();
        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void filterMismatch_doesNotPublish() {
        when(repository.findByTenantIdAndTargetTypeAndTargetIdAndDeleted(1L, "ASSET", 1L, 0))
                .thenReturn(List.of(sub(7L, "quality")));   // 只订阅 quality
        boolean published = service().notifyChange(1L, "ASSET", 1L, "schema", Map.of());
        assertThat(published).isFalse();
        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }
}
