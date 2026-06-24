package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.LineageGraphService;
import com.dataweave.master.application.LineageGraphService.GraphNode;
import com.dataweave.master.application.LineageGraphService.LineageGraph;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 表级血缘图 REST（table-lineage）：态势驾驶舱活血缘图取数。
 * 全局分层图 + 以表为中心的 N 跳邻域 + 上下游影响分析。tenant/project 当前固定 1/1。
 */
@RestController
@RequestMapping("/api/lineage")
public class LineageGraphController {

    private static final long TENANT = 1L;
    private static final long PROJECT = 1L;

    private final LineageGraphService lineageGraphService;

    public LineageGraphController(LineageGraphService lineageGraphService) {
        this.lineageGraphService = lineageGraphService;
    }

    /** 全局血缘图（所有表节点 + 表→表流边）。 */
    @GetMapping("/graph")
    public ApiResponse<LineageGraph> graph() {
        return ApiResponse.ok(lineageGraphService.globalGraph(TENANT, PROJECT));
    }

    /** 以某表为中心、depth 跳邻域子图（默认 2 跳）。 */
    @GetMapping("/tables/{id}/neighborhood")
    public ApiResponse<LineageGraph> neighborhood(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "2") int depth) {
        return ApiResponse.ok(lineageGraphService.neighborhood(TENANT, PROJECT, id, depth));
    }

    /** 某表的上游（谁产出了它）。 */
    @GetMapping("/tables/{id}/upstream")
    public ApiResponse<List<GraphNode>> upstream(@PathVariable Long id) {
        return ApiResponse.ok(lineageGraphService.upstream(TENANT, PROJECT, id));
    }

    /** 某表的下游影响（谁消费了它）。 */
    @GetMapping("/tables/{id}/downstream")
    public ApiResponse<List<GraphNode>> downstream(@PathVariable Long id) {
        return ApiResponse.ok(lineageGraphService.downstream(TENANT, PROJECT, id));
    }
}
