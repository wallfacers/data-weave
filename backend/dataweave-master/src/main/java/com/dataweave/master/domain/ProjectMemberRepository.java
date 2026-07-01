package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ProjectMemberRepository extends CrudRepository<ProjectMember, Long> {

    List<ProjectMember> findByProjectId(Long projectId);
    List<ProjectMember> findByTenantId(Long tenantId);
    List<ProjectMember> findByUserId(Long userId);

    /** 036 项目作用域成员校验：统计 (tenant, project, user) 未删除成员行数（>0 即为成员）。 */
    long countByTenantIdAndProjectIdAndUserIdAndDeleted(Long tenantId, Long projectId, Long userId, Integer deleted);

    /** 036-D 角色/权限解析：取 (tenant, project, user) 的未删除成员行（含 role_id），非成员返回空表。 */
    List<ProjectMember> findByTenantIdAndProjectIdAndUserIdAndDeleted(Long tenantId, Long projectId, Long userId, Integer deleted);
}
