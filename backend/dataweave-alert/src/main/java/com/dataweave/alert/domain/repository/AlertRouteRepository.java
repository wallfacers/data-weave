package com.dataweave.alert.domain.repository;

import com.dataweave.alert.domain.AlertRoute;
import java.util.List;
import java.util.Optional;

public interface AlertRouteRepository {
    Optional<AlertRoute> findById(Long id);
    List<AlertRoute> findByTenantId(Long tenantId);
    /** 036: 按项目隔离的查询方法。 */
    List<AlertRoute> findByTenantIdAndProjectId(Long tenantId, Long projectId);
    AlertRoute save(AlertRoute route);
    int deleteById(Long id);
}
