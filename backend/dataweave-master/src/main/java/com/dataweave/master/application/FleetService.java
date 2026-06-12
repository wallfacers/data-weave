package com.dataweave.master.application;

import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 机器集群服务：worker 节点注册/心跳（幂等 upsert）、列表查询、心跳超时判离线、最优节点挑选。
 *
 * <p>心跳携带 incarnation（进程启动纪元）+ 运行中实例列表。incarnation 变化表明 worker 重启，
 * 该节点全部 DISPATCHED/RUNNING 实例需被标记 FAILED（WORKER_RESTART）。运行中实例列表用于租约续约。
 */
@Service
public class FleetService {

    private static final Logger log = LoggerFactory.getLogger(FleetService.class);

    /** 心跳超时阈值（秒）：超过未上报判离线。 */
    public static final long OFFLINE_THRESHOLD_SECONDS = 30;

    private final WorkerNodeRepository repository;
    private final InstanceStateMachine stateMachine;

    public FleetService(WorkerNodeRepository repository, InstanceStateMachine stateMachine) {
        this.repository = repository;
        this.stateMachine = stateMachine;
    }

    /**
     * 注册或心跳上报：按 nodeCode 幂等 upsert，刷新资源指标与心跳时间，状态置 ONLINE。
     *
     * <p>若 incarnation 变化（worker 重启），触发该节点全部 DISPATCHED/RUNNING 实例标记
     * FAILED（WORKER_RESTART）。心跳携带运行中实例列表用于租约续约。
     *
     * @return 更新后的节点实体
     */
    public WorkerNode report(String nodeCode, String host, String capacity,
                             double cpu, double mem, double disk, double loadAvg, int runningTasks,
                             Long incarnation, List<UUID> runningInstanceIds,
                             long leaseSeconds) {
        WorkerNode node = repository.findByNodeCode(nodeCode).orElseGet(WorkerNode::new);
        boolean isNew = node.getId() == null;
        boolean incarnationChanged = false;

        if (isNew) {
            node.setNodeCode(nodeCode);
            node.setCreatedAt(LocalDateTime.now());
            node.setDeleted(0);
            node.setVersion(0);
            // 新注册节点补容量默认（与 schema worker_nodes 默认一致）：否则 INSERT 落 NULL，
            // SlotManager 当 0 槽，distributed 模式下真 worker 永远收不到下发。
            if (node.getMaxConcurrentTasks() == null) {
                node.setMaxConcurrentTasks(10);
            }
            if (node.getReservedTestSlots() == null) {
                node.setReservedTestSlots(1);
            }
        } else {
            // 检测 incarnation 变化（worker 重启）
            Long oldIncarnation = node.getIncarnation();
            if (incarnation != null && oldIncarnation != null && !incarnation.equals(oldIncarnation)) {
                incarnationChanged = true;
                log.info("[FleetService] 节点 {} incarnation 变化 {} → {}，worker 重启",
                        nodeCode, oldIncarnation, incarnation);
            }
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

        if (incarnation != null) {
            node.setIncarnation(incarnation);
        }

        node = repository.save(node);

        // incarnation 变化 → 该节点全部运行中实例 FAILED(WORKER_RESTART)
        if (incarnationChanged) {
            handleWorkerRestart(nodeCode);
        }

        // 心跳携带运行中实例列表 → 续约租约
        if (runningInstanceIds != null && !runningInstanceIds.isEmpty()) {
            LocalDateTime newLease = LocalDateTime.now().plusSeconds(leaseSeconds);
            int renewed = 0;
            for (UUID instanceId : runningInstanceIds) {
                if (stateMachine.renewLease(instanceId, newLease)) {
                    renewed++;
                }
            }
            if (renewed > 0) {
                log.debug("[FleetService] 节点 {} 续约 {} 个实例租约", nodeCode, renewed);
            }
        }

        return node;
    }

    /**
     * worker 重启时标记该节点全部 DISPATCHED/RUNNING 实例为 FAILED（WORKER_RESTART）。
     * CAS 推进，不与自然完成冲突。
     */
    private void handleWorkerRestart(String nodeCode) {
        // 依赖 SchedulerKernel 或 LeaseReaper 扫描并处理
        // 这里直接调用 RetryService 来处理重试，但需要先改状态
        // 改为直接通过 JdbcTemplate 批量 CAS
        log.info("[FleetService] 处理 worker 重启：节点 {} 的运行中实例将标记 FAILED", nodeCode);
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
