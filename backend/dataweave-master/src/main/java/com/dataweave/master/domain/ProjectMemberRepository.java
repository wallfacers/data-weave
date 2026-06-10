package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ProjectMemberRepository extends CrudRepository<ProjectMember, Long> {

    List<ProjectMember> findByProjectId(Long projectId);
}
