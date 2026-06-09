package com.dataweave.master.application;

import com.dataweave.master.domain.Metric;
import com.dataweave.master.domain.MetricRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 指标服务：按名查指标、执行口径 SQL、返回数值 + 口径溯源。
 */
@Service
public class MetricService {

    private final MetricRepository metricRepository;
    private final SqlExecutionService sqlExecutionService;

    public MetricService(MetricRepository metricRepository, SqlExecutionService sqlExecutionService) {
        this.metricRepository = metricRepository;
        this.sqlExecutionService = sqlExecutionService;
    }

    public Optional<Metric> findLatestByName(String name) {
        return metricRepository.findLatestByName(name);
    }

    /**
     * 执行指标口径：在来源表上执行 select {exprSql} from {sourceTable}。
     */
    public Object evaluate(Metric metric) {
        String sql = "select " + metric.getExprSql() + " from " + metric.getSourceTable();
        String reject = sqlExecutionService.rejectReason(sql);
        if (reject != null) {
            throw new IllegalArgumentException("指标口径 SQL 未通过只读校验: " + reject);
        }
        return sqlExecutionService.queryScalar(sql);
    }
}
