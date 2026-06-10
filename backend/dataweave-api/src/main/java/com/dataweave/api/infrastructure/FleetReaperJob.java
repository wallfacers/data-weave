package com.dataweave.api.infrastructure;

import com.dataweave.master.application.FleetService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时心跳超时检测：把心跳超 OFFLINE_THRESHOLD_SECONDS 的 ONLINE 节点标记为 OFFLINE。
 */
@Component
public class FleetReaperJob {

    private static final System.Logger log = System.getLogger(FleetReaperJob.class.getName());

    private final FleetService fleetService;

    public FleetReaperJob(FleetService fleetService) {
        this.fleetService = fleetService;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 60000)
    public void reap() {
        int marked = fleetService.markStaleOffline();
        if (marked > 0) {
            log.log(System.Logger.Level.INFO, "标记 {0} 个节点离线", marked);
        }
    }
}
