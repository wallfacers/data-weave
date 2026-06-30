package com.dataweave.alert.domain.repository;

import com.dataweave.alert.domain.AlertRoute;
import java.util.List;
import java.util.Optional;

public interface AlertRouteRepository {
    Optional<AlertRoute> findById(Long id);
    List<AlertRoute> findByTenantId(Long tenantId);
    AlertRoute save(AlertRoute route);
    int deleteById(Long id);
}
