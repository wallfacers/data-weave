package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
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
     * @param taskName 任务名模糊搜索（可选）
     * @param tiers    时效分档过滤，逗号分隔（可选；FRESH/AGING/STALE/NEVER）
     * @param sort     排序：worst_first（默认，最陈旧在前）/ best_first
     * @param page     页码（0-based，默认 0）
     * @param size     每页大小（默认 20）
     */
    @GetMapping
    public ApiResponse<PageResult<FreshnessRow>> query(
            @RequestParam(required = false) String taskName,
            @RequestParam(required = false) String tiers,
            @RequestParam(defaultValue = "worst_first") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<String> tierList = (tiers != null && !tiers.isBlank())
                ? Arrays.asList(tiers.split(","))
                : Collections.emptyList();

        return ApiResponse.ok(freshnessService.query(taskName, tierList, sort, page - 1, size));
    }
}
