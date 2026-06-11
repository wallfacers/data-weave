package com.dataweave.master.application;

import com.dataweave.master.application.SchedulingPolicy.NodeLoad;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位管理（design D6 work-conserving + TEST 预留槽）。
 *
 * <p>占用以 DB 为唯一真相源派生（{@code DISPATCHED/RUNNING} 计数），无内存漂移（认领即占、回报终态即释）。
 * 每节点预留 {@code reserved_test_slots} 个 TEST 槽（默认 1，可配 0）：NORMAL 任务只可用
 * {@code maxConcurrentTasks − reservedTestSlots} 个非预留槽，防试跑被例行任务饿死；TEST 可用全部空槽。
 */
@Service
public class SlotManager {

    private final WorkerNodeRepository nodeRepository;
    private final JdbcTemplate jdbc;

    public SlotManager(WorkerNodeRepository nodeRepository, JdbcTemplate jdbc) {
        this.nodeRepository = nodeRepository;
        this.jdbc = jdbc;
    }

    /** NORMAL 调度可用节点：容量扣除预留 TEST 槽。 */
    public List<NodeLoad> availableForNormal() {
        return build(false);
    }

    /** TEST 调度可用节点：容量为全部槽（含预留）。 */
    public List<NodeLoad> availableForTest() {
        return build(true);
    }

    private List<NodeLoad> build(boolean includeReserved) {
        Map<String, Integer> usedByNode = usedCounts();
        List<NodeLoad> out = new ArrayList<>();
        for (WorkerNode node : nodeRepository.findByStatus("ONLINE")) {
            int cap = node.getMaxConcurrentTasks() == null ? 0 : node.getMaxConcurrentTasks();
            int reserved = node.getReservedTestSlots() == null ? 0 : node.getReservedTestSlots();
            int capacity = includeReserved ? cap : Math.max(0, cap - reserved);
            int used = usedByNode.getOrDefault(node.getNodeCode(), 0);
            out.add(new NodeLoad(node, used, capacity));
        }
        return out;
    }

    /**
     * 在线节点的原始负载快照（capacity=maxConcurrentTasks 全量槽），供调度内核做轮内贪心分配时
     * 按 reserved_test_slots 自行区分 NORMAL/TEST 可用槽。
     */
    public List<NodeLoad> snapshotOnline() {
        Map<String, Integer> usedByNode = usedCounts();
        List<NodeLoad> out = new ArrayList<>();
        for (WorkerNode node : nodeRepository.findByStatus("ONLINE")) {
            int cap = node.getMaxConcurrentTasks() == null ? 0 : node.getMaxConcurrentTasks();
            int used = usedByNode.getOrDefault(node.getNodeCode(), 0);
            out.add(new NodeLoad(node, used, cap));
        }
        return out;
    }

    /** 各节点当前占用槽数（DISPATCHED + RUNNING）。 */
    private Map<String, Integer> usedCounts() {
        Map<String, Integer> map = new HashMap<>();
        jdbc.query(
                "SELECT worker_node_code, COUNT(*) AS cnt FROM task_instance "
                        + "WHERE state IN ('DISPATCHED','RUNNING') AND worker_node_code IS NOT NULL AND deleted=0 "
                        + "GROUP BY worker_node_code",
                rs -> {
                    map.put(rs.getString("worker_node_code"), rs.getInt("cnt"));
                });
        return map;
    }

    /** 全局是否还有 NORMAL 空槽（work-conserving 快速判定）。 */
    public boolean hasFreeNormalSlot() {
        return availableForNormal().stream().anyMatch(n -> n.free() > 0);
    }
}
