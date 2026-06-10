package com.dataweave.alert.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface AlertRuleRepository extends CrudRepository<AlertRule, Long> {

    List<AlertRule> findByProjectId(Long projectId);
}
