package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.MetricService;
import com.dataweave.master.domain.AtomicMetric;
import com.dataweave.master.i18n.BizException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 指标看板查询 REST 端点：当前项目每个 code 的最新版本指标 + 按口径求值的最新值。
 *
 * <p>供 Workspace 的「业务报表」Pinned 视图拉取。口径不可篡改 —— 这里只读最新
 * version，求值失败（口径 SQL 校验不过/表不存在/所选业务日期无数据）返回 value=null，
 * 由前端呈现明确空态。
 *
 * <p>036 FR-012：按当前请求的 projectId 过滤（消除全租户裸查）；支持按业务日期观察当日
 * 快照（{@code bizDate}，{@code yyyy-MM-dd}），缺数据 → value=null 空态，禁止借显他项目/他日期值。
 * projectId 解析：优先 {@link TenantContext#projectId()}（地基注入），回退 {@code projectId} 查询参数。
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

    /**
     * 指标看板。
     *
     * @param projectId 项目 id（可选；地基未注入 TenantContext 时由此传入）
     * @param bizDate   业务日期（可选；{@code yyyy-MM-dd}，源表按 {@code biz_date} 列收敛到当日快照）
     */
    @GetMapping
    public ApiResponse<List<MetricCard>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String bizDate) {
        Long pid = resolveProjectId(projectId);
        return ApiResponse.ok(metricService.listLatest(pid).stream()
                .map(m -> toCard(m, bizDate))
                .toList());
    }

    private MetricCard toCard(AtomicMetric m, String bizDate) {
        Object value;
        try {
            value = metricService.evaluate(m, bizDate);
        } catch (RuntimeException e) {
            value = null;
        }
        return new MetricCard(
                m.getId(), m.getCode(), m.getName(), m.getUnit(),
                m.getVersionNo(), m.getStatus(), value);
    }

    /** projectId：优先 TenantContext（地基注入、已校验成员归属），回退查询参数；皆空 → project.required。 */
    private static Long resolveProjectId(Long requestParam) {
        Long fromContext = TenantContext.projectId();
        Long pid = fromContext != null ? fromContext : requestParam;
        if (pid == null || pid <= 0) {
            throw new BizException("project.required");
        }
        return pid;
    }
}
