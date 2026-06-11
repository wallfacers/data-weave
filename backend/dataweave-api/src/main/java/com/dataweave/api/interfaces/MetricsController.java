package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.MetricService;
import com.dataweave.master.domain.AtomicMetric;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 指标看板查询 REST 端点：每个 code 的最新版本指标 + 按口径求值的最新值。
 *
 * <p>供 Workspace 的「业务报表」Pinned 视图拉取。口径不可篡改 —— 这里只读最新
 * version，求值失败（口径 SQL 校验不过/表不存在）返回 value=null，由前端呈现空态。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    /** 指标卡片：名称、口径版本、最新值（求值失败为 null）。 */
    public record MetricCard(
            Long id,
            String code,
            String name,
            String unit,
            Integer versionNo,
            String status,
            Object value) {
    }

    private final MetricService metricService;

    public MetricsController(MetricService metricService) {
        this.metricService = metricService;
    }

    @GetMapping
    public ApiResponse<List<MetricCard>> list() {
        return ApiResponse.ok(metricService.listLatest().stream()
                .map(this::toCard)
                .toList());
    }

    private MetricCard toCard(AtomicMetric m) {
        Object value;
        try {
            value = metricService.evaluate(m);
        } catch (RuntimeException e) {
            value = null;
        }
        return new MetricCard(
                m.getId(), m.getCode(), m.getName(), m.getUnit(),
                m.getVersionNo(), m.getStatus(), value);
    }
}
