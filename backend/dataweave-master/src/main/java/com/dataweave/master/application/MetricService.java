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
import java.util.regex.Pattern;

/**
 * 指标服务：按 code 查最新版本指标、执行口径 SQL。
 */
@Service
public class MetricService {

    /** 业务日期观察过滤列（与 task_instance / 源表约定一致：VARCHAR(32) yyyy-MM-dd）。 */
    private static final String BIZ_DATE_COLUMN = "biz_date";
    /** 严格 yyyy-MM-dd；仅数字与连字符，校验通过后拼接是注入安全的。 */
    private static final Pattern BIZ_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final AtomicMetricRepository atomicMetricRepository;
    private final SqlExecutionService sqlExecutionService;

    public MetricService(AtomicMetricRepository atomicMetricRepository,
                         SqlExecutionService sqlExecutionService) {
        this.atomicMetricRepository = atomicMetricRepository;
        this.sqlExecutionService = sqlExecutionService;
    }

    /**
     * 按 code 取最高版本的原子指标（项目作用域）。
     *
     * <p>036 FR-012：指标读接口按 projectId 过滤。指标看板 / 上架选指标均走此入口，
     * 杜绝跨项目同 code 串号。
     */
    public Optional<AtomicMetric> findLatestByCode(Long projectId, String code) {
        return atomicMetricRepository.findFirstByProjectIdAndCodeOrderByVersionNoDesc(projectId, code);
    }

    /**
     * 按 code 取最高版本的原子指标（无项目作用域）。
     *
     * <p>遗留入口：alert 轮询（{@code MetricPollEvaluator}）、MCP 指标求值等跨模块调用
     * 沿用。其项目隔离由各自所属域负责（alert→C 路）。指标看板请用
     * {@link #findLatestByCode(Long, String)}。
     */
    public Optional<AtomicMetric> findLatestByCode(String code) {
        return atomicMetricRepository.findFirstByCodeOrderByVersionNoDesc(code);
    }

    /**
     * 当前项目的全部指标，每个 code 只取最高版本，按 code 排序（报表看板用）。
     *
     * <p>036 FR-012：以 {@code projectId} 收敛，消除 {@code findAll()} 全租户裸查。
     */
    public List<AtomicMetric> listLatest(Long projectId) {
        Map<String, AtomicMetric> latestByCode = new LinkedHashMap<>();
        for (AtomicMetric m : atomicMetricRepository.findByProjectId(projectId)) {
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
        return evaluate(metric, null);
    }

    /**
     * 执行指标口径，可选按业务日期观察（036 FR-012）。
     *
     * <p>{@code bizDate} 非空且严格匹配 {@code yyyy-MM-dd} 时，追加
     * {@code WHERE biz_date = '<date>'} 收敛到当日快照；源表无 {@code biz_date} 列或当日
     * 无数据时求值抛异常，由调用方兜底为 {@code value=null}（前端呈现明确空态，禁止借显
     * 他日期/他项目值）。{@code bizDate} 经正则白名单校验（仅数字与连字符），拼接注入安全。
     */
    public Object evaluate(AtomicMetric metric, String bizDate) {
        String sql = "select " + metric.getMeasureExpr() + " from " + metric.getSourceTable();
        if (bizDate != null && BIZ_DATE_PATTERN.matcher(bizDate).matches()) {
            sql += " WHERE " + BIZ_DATE_COLUMN + " = '" + bizDate + "'";
        }
        String reject = sqlExecutionService.rejectReason(sql);
        if (reject != null) {
            throw new BizException("metric.sql_readonly_reject", reject);
        }
        return sqlExecutionService.queryScalar(sql);
    }
}
