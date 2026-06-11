package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface UserRoleRepository extends CrudRepository<UserRole, Long> {

    List<UserRole> findByUserId(Long userId);
    List<UserRole> findByTenantId(Long tenantId);
    List<UserRole> findByRoleId(Long roleId);
    void deleteByUserId(Long userId);
}
