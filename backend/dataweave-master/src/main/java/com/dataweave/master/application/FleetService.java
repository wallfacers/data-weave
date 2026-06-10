package com.dataweave.master.application;

import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 机器集群服务：worker 节点注册/心跳（幂等 upsert）、列表查询、心跳超时判离线、最优节点挑选。
 */
@Service
public class FleetService {

    /** 心跳超时阈值（秒）：超过未上报判离线。 */
    public static final long OFFLINE_THRESHOLD_SECONDS = 30;

    private final WorkerNodeRepository repository;

    public FleetService(WorkerNodeRepository repository) {
        this.repository = repository;
    }

    /**
     * 注册或心跳上报：按 nodeCode 幂等 upsert，刷新资源指标与心跳时间，状态置 ONLINE。
     */
    public WorkerNode report(String nodeCode, String host, String capacity,
                             double cpu, double mem, double disk, double loadAvg, int runningTasks) {
        WorkerNode node = repository.findByNodeCode(nodeCode).orElseGet(WorkerNode::new);
        boolean isNew = node.getId() == null;
        if (isNew) {
            node.setNodeCode(nodeCode);
            node.setCreatedAt(LocalDateTime.now());
            node.setDeleted(0);
            node.setVersion(0);
        }
        node.setHost(host);
        node.setCapacity(capacity);
        node.setCpu(cpu);
        node.setMem(mem);
        node.setDisk(disk);
        node.setLoadAvg(loadAvg);
        node.setRunningTasks(runningTasks);
        node.setStatus("ONLINE");
        node.setLastHeartbeat(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        return repository.save(node);
    }

    /** 所有 worker 节点，按 nodeCode 升序。 */
    public List<WorkerNode> nodes() {
        List<WorkerNode> all = new ArrayList<>();
        repository.findAll().forEach(all::add);
        all.sort(Comparator.comparing(WorkerNode::getNodeCode, Comparator.nullsLast(Comparator.naturalOrder())));
        return all;
    }

    /** 按 nodeCode 查单节点。 */
    public Optional<WorkerNode> node(String nodeCode) {
        return repository.findByNodeCode(nodeCode);
    }

    /** 挑选当前负载最低（内存使用率最小）的 ONLINE 节点，用于新任务/迁移落点。 */
    public Optional<WorkerNode> pickLeastLoadedOnline() {
        return repository.findByStatus("ONLINE").stream()
                .min(Comparator.comparing(n -> n.getMem() == null ? 0d : n.getMem()));
    }

    /**
     * 把心跳超时的 ONLINE 节点标记为 OFFLINE。
     *
     * @return 本次被标记离线的节点数
     */
    public int markStaleOffline() {
        LocalDateTime deadline = LocalDateTime.now().minus(Duration.ofSeconds(OFFLINE_THRESHOLD_SECONDS));
        int marked = 0;
        for (WorkerNode node : repository.findByStatus("ONLINE")) {
            LocalDateTime hb = node.getLastHeartbeat();
            if (hb != null && hb.isBefore(deadline)) {
                node.setStatus("OFFLINE");
                node.setUpdatedAt(LocalDateTime.now());
                repository.save(node);
                marked++;
            }
        }
        return marked;
    }
}
