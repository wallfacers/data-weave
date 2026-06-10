package com.dataweave.api.interfaces;

import com.dataweave.api.interfaces.dto.HeartbeatRequest;
import com.dataweave.master.application.FleetService;
import com.dataweave.master.domain.WorkerNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 机器集群 REST 端点：节点列表、单节点查询、心跳上报。
 */
@RestController
@RequestMapping("/api/fleet")
public class FleetController {

    private final FleetService fleetService;

    public FleetController(FleetService fleetService) {
        this.fleetService = fleetService;
    }

    @GetMapping
    public List<WorkerNode> listNodes() {
        return fleetService.nodes();
    }

    @GetMapping("/{nodeCode}")
    public WorkerNode getNode(@PathVariable String nodeCode) {
        return fleetService.node(nodeCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Worker node not found: " + nodeCode));
    }

    @PostMapping("/heartbeat")
    public WorkerNode heartbeat(@RequestBody HeartbeatRequest req) {
        return fleetService.report(
                req.getNodeCode(),
                req.getHost(),
                req.getCapacity(),
                req.getCpu() != null ? req.getCpu() : 0,
                req.getMem() != null ? req.getMem() : 0,
                req.getDisk() != null ? req.getDisk() : 0,
                req.getLoadAvg() != null ? req.getLoadAvg() : 0,
                req.getRunningTasks() != null ? req.getRunningTasks() : 0
        );
    }
}
