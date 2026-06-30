package com.dataweave.alert.application;

import com.dataweave.alert.domain.AlertChannel;
import com.dataweave.alert.domain.EventSubscription;
import com.dataweave.alert.domain.HealthEvent;
import com.dataweave.alert.domain.repository.AlertChannelRepository;
import com.dataweave.alert.domain.repository.EventSubscriptionRepository;
import com.dataweave.alert.domain.repository.HealthEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 027 US3：matchAndDispatch —— 匹配订阅经 026 通道分发；不匹配不分发；分发失败不外抛。
 */
class EventSubscriptionDispatchTest {

    private EventSubscriptionRepository subRepo;
    private AlertChannelRepository channelRepo;
    private AlertDispatchService dispatchService;
    private EventCenterService service;

    @BeforeEach
    void setUp() {
        subRepo = mock(EventSubscriptionRepository.class);
        channelRepo = mock(AlertChannelRepository.class);
        dispatchService = mock(AlertDispatchService.class);
        service = new EventCenterService(mock(HealthEventRepository.class), subRepo, channelRepo, dispatchService);

        AlertChannel ch = new AlertChannel();
        ch.setId(5L); ch.setTenantId(10L); ch.setType("EMAIL"); ch.setEnabled(1); ch.setName("ops");
        when(channelRepo.findById(5L)).thenReturn(Optional.of(ch));
    }

    private HealthEvent event(String type, String severity) {
        HealthEvent e = new HealthEvent();
        e.setTenantId(10L); e.setType(type); e.setSeverity(severity); e.setFingerprint("fp");
        return e;
    }

    private EventSubscription sub(String typeFilter, String minSeverity) {
        EventSubscription s = new EventSubscription();
        s.setId(1L); s.setTenantId(10L); s.setTypeFilter(typeFilter); s.setMinSeverity(minSeverity);
        s.setChannelId(5L); s.setEnabled(1);
        return s;
    }

    @Test
    void dispatches_when_subscription_matches() {
        when(subRepo.findEnabledByTenantId(10L)).thenReturn(List.of(sub("QUALITY_FAILED", "MEDIUM")));

        service.matchAndDispatch(event("QUALITY_FAILED", "HIGH")); // HIGH ≥ MEDIUM

        verify(dispatchService).dispatchToChannel(any(), any());
    }

    @Test
    void no_dispatch_when_type_mismatches() {
        when(subRepo.findEnabledByTenantId(10L)).thenReturn(List.of(sub("SLA_BREACH", null)));

        service.matchAndDispatch(event("QUALITY_FAILED", "HIGH"));

        verify(dispatchService, never()).dispatchToChannel(any(), any());
    }

    @Test
    void no_dispatch_when_below_min_severity() {
        when(subRepo.findEnabledByTenantId(10L)).thenReturn(List.of(sub("QUALITY_FAILED", "CRITICAL")));

        service.matchAndDispatch(event("QUALITY_FAILED", "LOW")); // LOW < CRITICAL

        verify(dispatchService, never()).dispatchToChannel(any(), any());
    }

    @Test
    void dispatch_failure_does_not_propagate() {
        when(subRepo.findEnabledByTenantId(10L)).thenReturn(List.of(sub("QUALITY_FAILED", null)));
        when(dispatchService.dispatchToChannel(any(), any()))
                .thenThrow(new DataIntegrityViolationException("boom"));

        // 不应抛出（不阻断持久化/其它订阅）
        service.matchAndDispatch(event("QUALITY_FAILED", "HIGH"));

        verify(dispatchService).dispatchToChannel(any(), any());
    }
}
