package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface PolicyRuleRepository extends CrudRepository<PolicyRule, Long> {

    List<PolicyRule> findByEnabledOrderBySortOrderAscIdAsc(Integer enabled);
}
