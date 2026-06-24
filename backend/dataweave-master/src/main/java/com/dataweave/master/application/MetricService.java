package com.dataweave.master.application;

import com.dataweave.master.domain.AtomicMetric;
import com.dataweave.master.domain.AtomicMetricRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** 全部指标，每个 code 只取最高版本，按 code 排序（报表看板用）。 */
    public List<AtomicMetric> listLatest() {
        Map<String, AtomicMetric> latestByCode = new LinkedHashMap<>();
        for (AtomicMetric m : atomicMetricRepository.findAll()) {
            AtomicMetric cur = latestByCode.get(m.getCode());
            if (cur == null || versionNo(m) > versionNo(cur)) {
                latestByCode.put(m.getCode(), m);
            }
        }
        List<AtomicMetric> result = new ArrayList<>(latestByCode.values());
        result.sort(Comparator.comparing(AtomicMetric::getCode));
        return result;
    }

    private static int versionNo(AtomicMetric m) {
        return m.getVersionNo() == null ? 0 : m.getVersionNo();
    }

    /**
     * 执行指标口径：select {measureExpr} from {sourceTable}。
     * 先过只读校验，通过后执行并返回聚合值。
     */
    public Object evaluate(AtomicMetric metric) {
        String sql = "select " + metric.getMeasureExpr() + " from " + metric.getSourceTable();
        String reject = sqlExecutionService.rejectReason(sql);
        if (reject != null) {
            throw new BizException("metric.sql_readonly_reject", reject);
        }
        return sqlExecutionService.queryScalar(sql);
    }
}
