package com.dataweave.alert.domain.repository;

import com.dataweave.alert.domain.AlertChannel;
import java.util.List;
import java.util.Optional;

public interface AlertChannelRepository {
    Optional<AlertChannel> findById(Long id);
    List<AlertChannel> findByTenantId(Long tenantId);
    List<AlertChannel> findByIds(Long tenantId, List<Long> ids);
    AlertChannel save(AlertChannel channel);
    int deleteById(Long id);
}
