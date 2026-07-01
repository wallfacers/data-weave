package com.dataweave.alert.domain.repository;

import com.dataweave.alert.domain.AlertChannel;
import java.util.List;
import java.util.Optional;

public interface AlertChannelRepository {
    Optional<AlertChannel> findById(Long id);
    List<AlertChannel> findByTenantId(Long tenantId);
    /** 036: 按项目隔离的查询方法。 */
    List<AlertChannel> findByTenantIdAndProjectId(Long tenantId, Long projectId);
    List<AlertChannel> findByIds(Long tenantId, List<Long> ids);
    AlertChannel save(AlertChannel channel);
    int deleteById(Long id);
}
