package com.dataweave.master.application;

import com.dataweave.master.domain.AtomicMetric;
import com.dataweave.master.domain.AtomicMetricRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 指标服务：按 code 查最新版本指标、执行口径 SQL。
 */
@Service
public class MetricService {

    private final AtomicMetricRepository atomicMetricRepository;
    private final SqlExecutionService sqlExecutionService;

    public MetricService(AtomicMetricRepository atomicMetricRepository,
                         SqlExecutionService sqlExecutionService) {
        this.atomicMetricRepository = atomicMetricRepository;
        this.sqlExecutionService = sqlExecutionService;
    }

    /** 按 code 取最高版本的原子指标。 */
    public Optional<AtomicMetric> findLatestByCode(String code) {
        return atomicMetricRepository.findFirstByCodeOrderByVersionNoDesc(code);
    }

    /**
     * 执行指标口径：select {measureExpr} from {sourceTable}。
     * 先过只读校验，通过后执行并返回聚合值。
     */
    public Object evaluate(AtomicMetric metric) {
        String sql = "select " + metric.getMeasureExpr() + " from " + metric.getSourceTable();
        String reject = sqlExecutionService.rejectReason(sql);
        if (reject != null) {
            throw new IllegalArgumentException("指标口径 SQL 未通过只读校验: " + reject);
        }
        return sqlExecutionService.queryScalar(sql);
    }
}
