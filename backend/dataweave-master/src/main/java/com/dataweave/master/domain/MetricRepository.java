package com.dataweave.master.domain;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

/**
 * 指标仓储。按名取最新版本用于查询/溯源。
 */
public interface MetricRepository extends CrudRepository<Metric, Long> {

    @Query("SELECT * FROM metrics WHERE name = :name ORDER BY version DESC LIMIT 1")
    Optional<Metric> findLatestByName(String name);
}
