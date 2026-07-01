package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ProjectMemberRepository extends CrudRepository<ProjectMember, Long> {

    List<ProjectMember> findByProjectId(Long projectId);
    List<ProjectMember> findByTenantId(Long tenantId);
    List<ProjectMember> findByUserId(Long userId);

    /** 036 项目作用域成员校验：统计 (tenant, project, user) 未删除成员行数（>0 即为成员）。 */
    long countByTenantIdAndProjectIdAndUserIdAndDeleted(Long tenantId, Long projectId, Long userId, Integer deleted);
}
