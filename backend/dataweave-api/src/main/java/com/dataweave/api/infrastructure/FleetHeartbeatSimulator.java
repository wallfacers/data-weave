package com.dataweave.api.infrastructure;

import com.dataweave.master.application.FleetService;
import com.dataweave.master.domain.WorkerNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模拟心跳刷新器（默认开启）。
 *
 * <p>遍历所有 ONLINE 节点，用其现有指标再次 report() 刷新心跳时间，保持在线状态。
 * 无独立 worker 进程时让驾驶舱看到「活着的集群」。OFFLINE 节点不动。
 */
@Component
public class FleetHeartbeatSimulator {

    private static final System.Logger log = System.getLogger(FleetHeartbeatSimulator.class.getName());

    private final FleetService fleetService;

    @Value("${dataweave.fleet.simulate:true}")
    private boolean simulate;

    public FleetHeartbeatSimulator(FleetService fleetService) {
        this.fleetService = fleetService;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 5000)
    public void simulateHeartbeats() {
        if (!simulate) {
            return;
        }
        List<WorkerNode> onlineNodes = fleetService.nodes().stream()
                .filter(n -> "ONLINE".equals(n.getStatus()))
                .toList();
        for (WorkerNode node : onlineNodes) {
            fleetService.report(
                    node.getNodeCode(),
                    node.getHost(),
                    node.getCapacity(),
                    node.getCpu() != null ? node.getCpu() : 0,
                    node.getMem() != null ? node.getMem() : 0,
                    node.getDisk() != null ? node.getDisk() : 0,
                    node.getLoadAvg() != null ? node.getLoadAvg() : 0,
                    node.getRunningTasks() != null ? node.getRunningTasks() : 0,
                    node.getIncarnation(),
                    null,
                    120
            );
            log.log(System.Logger.Level.DEBUG, "模拟心跳刷新: {0}", node.getNodeCode());
        }
    }
}
