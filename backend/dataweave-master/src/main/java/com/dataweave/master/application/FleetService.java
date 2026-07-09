package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
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
 * 该节点全部 DISPATCHED/RUNNING 实例需被 infra 回收重派（{@link #handleWorkerRestart}，节点级即时）。
 * 运行中实例列表用于租约续约（060 真续约）。
 *
 * <p>060 节点容错闭环：
 * <ul>
 *   <li><b>incarnation_since</b>：新注册/纪元变化时落 now，配合 SlotManager 稳定窗过滤假/重启节点（FR-002）。</li>
 *   <li><b>节点级即时回收（I1）</b>：incarnation 变化即 CAS 回收该节点全部活跃实例为 infra（不烧业务、不计熔断）。</li>
 *   <li><b>恢复唤醒（FR-014）</b>：节点由不可用→可用（OFFLINE 恢复 ONLINE）时主动发 WAKE 抽干等待任务。</li>
 *   <li><b>健康列保护（FR-006）</b>：既有节点心跳走 targeted UPDATE，不覆写并发写的 consecutive_infra_failures/
 *       quarantined_until，防心跳 clobber 熔断状态。</li>
 * </ul>
 */
@Service
public class FleetService {

    private static final Logger log = LoggerFactory.getLogger(FleetService.class);

    /** 心跳超时阈值（秒）：超过未上报判离线。 */
    public static final long OFFLINE_THRESHOLD_SECONDS = 30;

    private final WorkerNodeRepository repository;
    private final InstanceStateMachine stateMachine;
    private final JdbcTemplate jdbc;
    private final EventBus eventBus;
    private final long stabilizationWindowMs;
    private final int infraRedispatchMax;

    public FleetService(WorkerNodeRepository repository, InstanceStateMachine stateMachine,
                        JdbcTemplate jdbc, EventBus eventBus,
                        @Value("${scheduler.node.stabilization-window-ms:15000}") long stabilizationWindowMs,
                        @Value("${scheduler.infra-redispatch-max:10}") int infraRedispatchMax) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.jdbc = jdbc;
        this.eventBus = eventBus;
        this.stabilizationWindowMs = stabilizationWindowMs;
        this.infraRedispatchMax = infraRedispatchMax;
    }

    /**
     * 注册或心跳上报：按 nodeCode 幂等 upsert，刷新资源指标与心跳时间，状态置 ONLINE。
     *
     * <p>若 incarnation 变化（worker 重启）：① 落 incarnation_since=now（重置稳定窗）；② 节点级即时回收该节点
     * 全部 DISPATCHED/RUNNING 实例为 infra（{@link #handleWorkerRestart}）。心跳携带运行中实例列表用于租约续约。
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
        LocalDateTime now = LocalDateTime.now();
        // 060 恢复唤醒：记录更新前的可用性（基于加载的旧状态）
        boolean wasAvailable = NodeHealthService.isAvailable(node, now, stabilizationWindowMs,
                OFFLINE_THRESHOLD_SECONDS * 1000L);

        if (isNew) {
            node.setNodeCode(nodeCode);
            node.setCreatedAt(now);
            node.setDeleted(0);
            node.setVersion(0);
            // 新注册节点补容量默认（与 schema worker_nodes 默认一致）：否则 INSERT 落 NULL，
            // SlotManager 当 0 槽，distributed 模式下真 worker 永远收不到下发。
            if (node.getMaxConcurrentTasks() == null) {
                node.setMaxConcurrentTasks(100);
            }
            if (node.getReservedTestSlots() == null) {
                node.setReservedTestSlots(1);
            }
            node.setIncarnationSince(now);  // 060：新节点进入稳定窗（首个稳定窗内不被派）
        } else {
            // 检测 incarnation 变化（worker 重启）
            Long oldIncarnation = node.getIncarnation();
            if (incarnation != null && oldIncarnation != null && !incarnation.equals(oldIncarnation)) {
                incarnationChanged = true;
                node.setIncarnationSince(now);  // 060：纪元变化 → 重置稳定窗
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
        node.setLastHeartbeat(now);
        node.setUpdatedAt(now);

        if (incarnation != null) {
            node.setIncarnation(incarnation);
        }

        // 060 持久化：新节点 INSERT（健康列取 DB 默认）；既有节点 targeted UPDATE（不覆写并发写的健康列
        // consecutive_infra_failures/quarantined_until/incarnation_since-by-others，防心跳 clobber 熔断状态 FR-006）。
        if (isNew) {
            node = repository.save(node);
        } else {
            updateHeartbeatFields(node, now);
        }

        // 060（I1）：incarnation 变化 → 节点级即时回收该节点全部活跃实例（infra，不烧业务；WORKER_RESTART 不计熔断 D4）
        if (incarnationChanged) {
            handleWorkerRestart(nodeCode);
        }

        // 060（FR-014）：节点由不可用 → 可用（如 OFFLINE 恢复 ONLINE）→ 主动唤醒抽干等待任务（不靠兜底轮询）。
        // 纪元刚变时 nowAvailable=false（稳定窗内），不会误唤醒；稳定窗跨过由 StuckInstanceSweeper 唤醒。
        boolean nowAvailable = NodeHealthService.isAvailable(node, now, stabilizationWindowMs,
                OFFLINE_THRESHOLD_SECONDS * 1000L);
        if (!wasAvailable && nowAvailable) {
            eventBus.publish(InstanceStates.WAKE_CHANNEL, "node-recovered");
        }

        // 心跳携带运行中实例列表 → 续约租约
        if (runningInstanceIds != null && !runningInstanceIds.isEmpty()) {
            LocalDateTime newLease = now.plusSeconds(leaseSeconds);
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
     * 060（I1）：worker 重启 → CAS 回收该 worker_node_code 下全部 DISPATCHED/RUNNING 实例为 infra
     * （{@link InstanceStateMachine#reclaimInfra}：active→WAITING + infra_count++，不烧 business_attempt、
     * 超限→SUSPENDED）。WORKER_RESTART 不计熔断（正常重启由稳定窗处置，D4）。修复现状空壳。
     */
    private void handleWorkerRestart(String nodeCode) {
        List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM task_instance WHERE worker_node_code=? AND state IN ('DISPATCHED','RUNNING') AND deleted=0",
                UUID.class, nodeCode);
        for (UUID id : ids) {
            stateMachine.reclaimInfra(id, infraRedispatchMax);
        }
        if (!ids.isEmpty()) {
            eventBus.publish(InstanceStates.WAKE_CHANNEL, "worker-restart");
            log.info("[FleetService] 节点 {} 重启，节点级回收 {} 个活跃实例（infra，转移至健康节点）",
                    nodeCode, ids.size());
        }
    }

    /** 060：既有节点心跳 targeted UPDATE（保留并发写的健康列 consecutive_infra_failures/quarantined_until）。 */
    private void updateHeartbeatFields(WorkerNode node, LocalDateTime now) {
        jdbc.update("UPDATE worker_nodes SET host=?, capacity=?, cpu=?, mem=?, disk=?, load_avg=?, running_tasks=?, "
                        + "status=?, last_heartbeat=?, incarnation=?, incarnation_since=?, updated_at=? "
                        + "WHERE node_code=? AND deleted=0",
                node.getHost(), node.getCapacity(), node.getCpu(), node.getMem(), node.getDisk(), node.getLoadAvg(),
                node.getRunningTasks(), node.getStatus(), node.getLastHeartbeat(), node.getIncarnation(),
                node.getIncarnationSince(), now, node.getNodeCode());
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
     * 把心跳超时的 ONLINE 节点标记为 OFFLINE（060：@Scheduled 定时化，使 LeaseReaper 的 WORKER_LOST 路径生效；
     * targeted UPDATE 仅改 status，保留健康列）。
     *
     * @return 本次被标记离线的节点数
     */
    @Scheduled(fixedDelayString = "${scheduler.fleet.offline-sweep-ms:15000}", initialDelayString = "${scheduler.fleet.offline-sweep-initial-ms:30000}")
    public int markStaleOffline() {
        LocalDateTime deadline = LocalDateTime.now().minus(Duration.ofSeconds(OFFLINE_THRESHOLD_SECONDS));
        int marked = 0;
        for (WorkerNode node : repository.findByStatus("ONLINE")) {
            LocalDateTime hb = node.getLastHeartbeat();
            if (hb != null && hb.isBefore(deadline)) {
                // 060：targeted UPDATE（仅 status/updated_at），不 clobber 并发写的健康列
                jdbc.update("UPDATE worker_nodes SET status='OFFLINE', updated_at=? WHERE node_code=? AND deleted=0",
                        LocalDateTime.now(), node.getNodeCode());
                node.setStatus("OFFLINE");
                marked++;
            }
        }
        return marked;
    }
}
