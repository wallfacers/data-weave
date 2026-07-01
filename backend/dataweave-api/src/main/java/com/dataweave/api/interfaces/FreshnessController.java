package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.application.FreshnessService;
import com.dataweave.master.application.FreshnessService.FreshnessRow;
import com.dataweave.master.application.FreshnessService.PageResult;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 数据新鲜度聚合端点。
 *
 * <p>按任务聚合最近成功执行时间，分档为 FRESH/AGING/STALE/NEVER。
 * 支持分档筛选、任务名搜索、最陈旧优先排序、分页。
 *
 * <p>036 FR-016：按当前请求的 (tenantId, projectId) 作用域查询，消除全租户裸扫。
 * projectId 解析：优先取 {@link TenantContext#projectId()}（地基 JwtAuthFilter 注入），
 * 缺省回退到 {@code projectId} 查询参数（地基未注入前的兼容形态）；二者皆空 →
 * {@code project.required}。
 */
@RestController
@RequestMapping("/api/freshness")
public class FreshnessController {

    private final FreshnessService freshnessService;

    public FreshnessController(FreshnessService freshnessService) {
        this.freshnessService = freshnessService;
    }

    /**
     * 查询数据新鲜度。
     *
     * @param projectId 项目 id（可选；地基未注入 TenantContext 时由此传入）
     * @param taskName  任务名模糊搜索（可选）
     * @param tiers     时效分档过滤，逗号分隔（可选；FRESH/AGING/STALE/NEVER）
     * @param sort      排序：worst_first（默认，最陈旧在前）/ best_first
     * @param page      页码（1-based，默认 1）
     * @param size      每页大小（默认 20）
     */
    @GetMapping
    public ApiResponse<PageResult<FreshnessRow>> query(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String taskName,
            @RequestParam(required = false) String tiers,
            @RequestParam(defaultValue = "worst_first") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long tenantId = TenantContext.tenantId();
        Long resolvedProjectId = resolveProjectId(projectId);

        List<String> tierList = (tiers != null && !tiers.isBlank())
                ? Arrays.asList(tiers.split(","))
                : Collections.emptyList();

        return ApiResponse.ok(freshnessService.query(tenantId, resolvedProjectId, taskName, tierList, sort, page - 1, size));
    }

    /** projectId：优先 TenantContext（地基注入、已校验成员归属），回退查询参数。 */
    private static Long resolveProjectId(Long requestParam) {
        Long fromContext = TenantContext.projectId();
        Long pid = fromContext != null ? fromContext : requestParam;
        if (pid == null || pid <= 0) {
            throw new BizException("project.required");
        }
        return pid;
    }
}
