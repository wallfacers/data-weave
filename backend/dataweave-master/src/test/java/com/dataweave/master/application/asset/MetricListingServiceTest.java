package com.dataweave.master.application.asset;

import com.dataweave.master.application.MetricService;
import com.dataweave.master.domain.asset.MetricListing;
import com.dataweave.master.domain.asset.MetricListingRepository;
import com.dataweave.master.domain.asset.MetricReuseRef;
import com.dataweave.master.domain.asset.MetricReuseRefRepository;
import com.dataweave.master.i18n.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 指标复用防环单元测试（SC-004）。验证有向可达性：自环 / 直接环 / 非环。
 */
@ExtendWith(MockitoExtension.class)
class MetricListingServiceTest {

    @Mock MetricListingRepository listingRepository;
    @Mock MetricReuseRefRepository reuseRepository;
    @Mock MetricService metricService;
    @Mock JdbcTemplate jdbc;
    @Mock CatalogMetrics metrics;

    private MetricListingService service() {
        return new MetricListingService(listingRepository, reuseRepository, metricService, jdbc, metrics);
    }

    private void listingExists(long id) {
        MetricListing m = new MetricListing();
        m.setId(id);
        m.setTenantId(1L);
        m.setProjectId(1L);
        m.setStatus("LISTED");
        when(listingRepository.findByIdAndTenantIdAndDeleted(id, 1L, 0)).thenReturn(Optional.of(m));
    }

    private MetricReuseRef edge(long listingId, String consumerRef) {
        MetricReuseRef r = new MetricReuseRef();
        r.setListingId(listingId);
        r.setConsumerRef(consumerRef);
        r.setConsumerType("METRIC");
        return r;
    }

    @Test
    void selfReuse_isRejectedAsCycle() {
        listingExists(5L);
        lenient().when(reuseRepository.findByTenantIdAndProjectIdAndDeleted(1L, 1L, 0)).thenReturn(List.of());
        assertThatThrownBy(() -> service().reuse(1L, 1L, 1L, 5L, "METRIC", "5"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "catalog.reuse_cycle");
        verify(reuseRepository, never()).save(any());
    }

    @Test
    void directCycle_isRejected() {
        // 已有边：9 依赖 5（consumer_ref=9, listing_id=5）。再加 5 依赖 9（path=9, consumer=5）→ 成环。
        listingExists(9L);
        when(reuseRepository.findByTenantIdAndProjectIdAndDeleted(1L, 1L, 0))
                .thenReturn(List.of(edge(5L, "9")));
        assertThatThrownBy(() -> service().reuse(1L, 1L, 1L, 9L, "METRIC", "5"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "catalog.reuse_cycle");
        verify(reuseRepository, never()).save(any());
    }

    @Test
    void acyclicReuse_isPersisted() {
        listingExists(5L);
        when(reuseRepository.findByTenantIdAndProjectIdAndDeleted(1L, 1L, 0)).thenReturn(List.of());
        when(reuseRepository.save(any(MetricReuseRef.class))).thenAnswer(inv -> {
            MetricReuseRef r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });
        MetricReuseRef ref = service().reuse(1L, 1L, 1L, 5L, "METRIC", "9");
        assertThat(ref.getId()).isEqualTo(100L);
        assertThat(ref.getListingId()).isEqualTo(5L);
        assertThat(ref.getConsumerRef()).isEqualTo("9");
        verify(reuseRepository).save(any(MetricReuseRef.class));
    }

    @Test
    void transitiveCycle_isRejected() {
        // 链：A(7)依赖B(8)、B(8)依赖C(9)。再加 C(9) 依赖 A(7)（path=7, consumer=9）→ 9→7→8→9 成环。
        listingExists(7L);
        when(reuseRepository.findByTenantIdAndProjectIdAndDeleted(1L, 1L, 0))
                .thenReturn(List.of(edge(8L, "7"), edge(9L, "8")));
        assertThatThrownBy(() -> service().reuse(1L, 1L, 1L, 7L, "METRIC", "9"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "catalog.reuse_cycle");
    }
}
