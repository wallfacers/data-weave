package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface AgentActionRepository extends CrudRepository<AgentAction, Long> {

    List<AgentAction> findByStepIdInOrderByIdAsc(List<Long> stepIds);

    List<AgentAction> findByApprovalStatusOrderByIdAsc(String approvalStatus);
}
