package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.interfaces.dto.HeartbeatRequest;
import com.dataweave.master.application.FleetService;
import com.dataweave.master.domain.WorkerNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * 机器集群 REST 端点：节点列表、单节点查询、心跳上报。
 */
@RestController
@RequestMapping("/api/fleet")
public class FleetController {

    private final FleetService fleetService;
    private final long leaseSeconds;

    public FleetController(FleetService fleetService,
                           @Value("${scheduler.lease-seconds:120}") long leaseSeconds) {
        this.fleetService = fleetService;
        this.leaseSeconds = leaseSeconds;
    }

    @GetMapping
    public ApiResponse<List<WorkerNode>> listNodes() {
        return ApiResponse.ok(fleetService.nodes());
    }

    @GetMapping("/{nodeCode}")
    public ApiResponse<WorkerNode> getNode(@PathVariable String nodeCode) {
        WorkerNode node = fleetService.node(nodeCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Worker node not found: " + nodeCode));
        return ApiResponse.ok(node);
    }

    @PostMapping("/heartbeat")
    public ApiResponse<WorkerNode> heartbeat(@RequestBody HeartbeatRequest req) {
        // 解析运行中实例 ID 列表
        List<UUID> runningIds = null;
        if (req.getRunningInstanceIds() != null) {
            runningIds = req.getRunningInstanceIds().stream()
                    .map(id -> {
                        try { return UUID.fromString(id); }
                        catch (IllegalArgumentException e) { return null; }
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        return ApiResponse.ok(fleetService.report(
                req.getNodeCode(),
                req.getHost(),
                req.getCapacity(),
                req.getCpu() != null ? req.getCpu() : 0,
                req.getMem() != null ? req.getMem() : 0,
                req.getDisk() != null ? req.getDisk() : 0,
                req.getLoadAvg() != null ? req.getLoadAvg() : 0,
                req.getRunningTasks() != null ? req.getRunningTasks() : 0,
                req.getIncarnation(),
                runningIds,
                leaseSeconds
        ));
    }
}
