package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface RolePermissionRepository extends CrudRepository<RolePermission, Long> {

    List<RolePermission> findByRoleId(Long roleId);
}
