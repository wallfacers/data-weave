package com.dataweave.master.application;

import com.dataweave.master.domain.MasterNode;
import com.dataweave.master.domain.MasterNodeRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Master 成员注册与心跳（cron 分片用，Batch B）。
 * <p>
 * 启动时自注册（upsert），周期心跳续约，超时剔除离线 master。
 * 活 master 列表按 master_code 稳定排序，每个 master 取自己的 index 作为 shardIndex。
 */
@Component
public class MasterRegistry {

    private static final Logger log = LoggerFactory.getLogger(MasterRegistry.class);

    private final MasterNodeRepository repo;
    private final long incarnation;
    private final String masterCode;
    private final long heartbeatMs;
    private final long offlineThresholdSec;

    public MasterRegistry(MasterNodeRepository repo,
                          @Value("${scheduler.master-heartbeat-ms:10000}") long heartbeatMs,
                          @Value("${scheduler.master-offline-threshold-sec:30}") long offlineThresholdSec) {
        this.repo = repo;
        this.heartbeatMs = heartbeatMs;
        this.offlineThresholdSec = offlineThresholdSec;
        this.incarnation = ManagementFactory.getRuntimeMXBean().getStartTime();
        this.masterCode = buildMasterCode();
    }

    @PostConstruct
    void register() {
        Optional<MasterNode> existing = repo.findByMasterCode(masterCode);
        MasterNode node = existing.orElse(new MasterNode());
        node.setMasterCode(masterCode);
        node.setMasterUri("http://" + hostname() + ":" + port());
        node.setIncarnation(incarnation);
        node.setStatus("ONLINE");
        node.setLastHeartbeat(LocalDateTime.now());
        if (node.getCreatedAt() == null) {
            node.setCreatedAt(LocalDateTime.now());
        }
        node.setUpdatedAt(LocalDateTime.now());
        repo.save(node);
        log.info("[MasterRegistry] 注册完成 masterCode={} incarnation={}", masterCode, incarnation);
    }

    /** 心跳续约，周期 = scheduler.master-heartbeat-ms。 */
    @Scheduled(fixedDelayString = "${scheduler.master-heartbeat-ms:10000}")
    void heartbeat() {
        Optional<MasterNode> existing = repo.findByMasterCode(masterCode);
        if (existing.isPresent()) {
            MasterNode node = existing.get();
            node.setIncarnation(incarnation);
            node.setStatus("ONLINE");
            node.setLastHeartbeat(LocalDateTime.now());
            node.setUpdatedAt(LocalDateTime.now());
            repo.save(node);
        } else {
            // 记录不存在（被手动清理等），重新注册
            register();
        }
        // 剔除超时离线 master
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofSeconds(offlineThresholdSec));
        repo.markOffline(threshold, LocalDateTime.now());
    }

    /** 活 master 列表（ONLINE + 心跳未超时），按 master_code 排序。 */
    public List<MasterNode> activeMasters() {
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofSeconds(offlineThresholdSec));
        return repo.findActive(threshold);
    }

    /** 本 master 在活列表中的序号（0-based），用于分片。未注册返回 -1。 */
    public int myShardIndex() {
        List<MasterNode> actives = activeMasters();
        for (int i = 0; i < actives.size(); i++) {
            if (actives.get(i).getMasterCode().equals(masterCode)) {
                return i;
            }
        }
        return -1;
    }

    public int activeMasterCount() {
        return activeMasters().size();
    }

    private static String buildMasterCode() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "localhost";
        }
        long pid = ProcessHandle.current().pid();
        return host + "-" + pid;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    private static String port() {
        String port = System.getProperty("server.port");
        return port != null ? port : "8000";
    }
}
