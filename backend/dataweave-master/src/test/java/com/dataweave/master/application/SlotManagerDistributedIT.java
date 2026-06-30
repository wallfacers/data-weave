package com.dataweave.master.application;

import com.dataweave.master.application.SchedulingPolicy.NodeLoad;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 028: SlotManager integration test — 真实 H2 usedCounts 聚合。
 *
 * <p>使用嵌入式 H2 DataSource 验证 usedCounts SQL 在真实数据库上正确聚合
 * DISPATCHED/RUNNING 实例计数。不依赖 @SpringBootTest，避免上下文污染。
 */
class SlotManagerDistributedIT {

    private DataSource ds;
    private JdbcTemplate jdbc;
    private SlotManager slotManager;
    private WorkerNodeRepository nodeRepository;

    @BeforeEach
    void setUp() {
        ds = new SingleConnectionDataSource("jdbc:h2:mem:slotmgr_it;DB_CLOSE_DELAY=-1", true);
        jdbc = new JdbcTemplate(ds);
        // Schema: create minimal tables for SlotManager to query
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS worker_nodes (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    node_code VARCHAR(128),
                    host VARCHAR(256),
                    max_concurrent_tasks INTEGER,
                    reserved_test_slots INTEGER,
                    status VARCHAR(32),
                    incarnation BIGINT,
                    capacity VARCHAR(32),
                    cpu DOUBLE,
                    mem DOUBLE,
                    disk DOUBLE,
                    load_avg DOUBLE,
                    running_tasks INTEGER,
                    last_heartbeat TIMESTAMP,
                    created_at TIMESTAMP,
                    deleted INTEGER DEFAULT 0,
                    version INTEGER DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS task_instance (
                    id BIGINT PRIMARY KEY,
                    task_def_id BIGINT,
                    workflow_instance_id BIGINT,
                    state VARCHAR(32),
                    version_no INTEGER,
                    biz_date DATE,
                    tenant_id BIGINT,
                    project_id BIGINT,
                    worker_node_code VARCHAR(128),
                    created_at TIMESTAMP,
                    deleted INTEGER DEFAULT 0
                )
                """);
        // Minimal repository impl backed by our jdbc
        nodeRepository = new WorkerNodeRepository() {
            @Override public <S extends WorkerNode> S save(S entity) { return entity; }
            @Override public <S extends WorkerNode> Iterable<S> saveAll(Iterable<S> entities) { return entities; }
            @Override public java.util.Optional<WorkerNode> findById(Long id) { return java.util.Optional.empty(); }
            @Override public boolean existsById(Long id) { return false; }
            @Override public Iterable<WorkerNode> findAll() { return List.of(); }
            @Override public Iterable<WorkerNode> findAllById(Iterable<Long> ids) { return List.of(); }
            @Override public long count() { return 0; }
            @Override public void deleteById(Long id) {}
            @Override public void delete(WorkerNode entity) {}
            @Override public void deleteAllById(Iterable<? extends Long> ids) {}
            @Override public void deleteAll(Iterable<? extends WorkerNode> entities) {}
            @Override public void deleteAll() {}
            @Override public java.util.Optional<WorkerNode> findByNodeCode(String nodeCode) { return java.util.Optional.empty(); }
            @Override public List<WorkerNode> findByStatus(String status) {
                // Query from the embedded H2
                return jdbc.query(
                        "SELECT * FROM worker_nodes WHERE status=?",
                        (rs, rowNum) -> {
                            WorkerNode n = new WorkerNode();
                            n.setId(rs.getLong("id"));
                            n.setNodeCode(rs.getString("node_code"));
                            n.setHost(rs.getString("host"));
                            n.setMaxConcurrentTasks((Integer) rs.getObject("max_concurrent_tasks"));
                            n.setReservedTestSlots((Integer) rs.getObject("reserved_test_slots"));
                            n.setStatus(rs.getString("status"));
                            n.setIncarnation(rs.getLong("incarnation"));
                            n.setCapacity(rs.getString("capacity"));
                            n.setCpu(rs.getDouble("cpu"));
                            n.setMem(rs.getDouble("mem"));
                            n.setDisk(rs.getDouble("disk"));
                            n.setLoadAvg(rs.getDouble("load_avg"));
                            n.setRunningTasks(rs.getInt("running_tasks"));
                            return n;
                        },
                        status);
            }
        };
        slotManager = new SlotManager(nodeRepository, jdbc);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
        ((SingleConnectionDataSource) ds).destroy();
    }

    // ── helper ──
    private void insertWorker(String code, Integer maxTasks, Integer reserved, String status) {
        jdbc.update("INSERT INTO worker_nodes (node_code, host, max_concurrent_tasks, reserved_test_slots, status, incarnation, capacity, cpu, mem, disk, load_avg, running_tasks, last_heartbeat, created_at) " +
                "VALUES (?, '127.0.0.1:8100', ?, ?, ?, 1, '4C/8G', 30, 45, 60, 1.0, 0, NOW(), NOW())",
                code, maxTasks, reserved, status);
    }

    private void insertInstance(long id, String state, String nodeCode) {
        jdbc.update("INSERT INTO task_instance (id, task_def_id, workflow_instance_id, state, version_no, biz_date, tenant_id, project_id, worker_node_code, created_at, deleted) " +
                "VALUES (?, 1, 1, ?, 1, CURRENT_DATE, 1, 1, ?, NOW(), 0)",
                id, state, nodeCode);
    }

    @Test
    void usedCounts_reflectsRunningTasks() {
        insertWorker("w1", 5, 0, "ONLINE");
        insertWorker("w2", 5, 0, "ONLINE");

        // w1: 2 DISPATCHED + 1 RUNNING = 3 used
        insertInstance(30001L, "DISPATCHED", "w1");
        insertInstance(30002L, "DISPATCHED", "w1");
        insertInstance(30003L, "RUNNING", "w1");
        // w2: 1 RUNNING = 1 used
        insertInstance(30004L, "RUNNING", "w2");

        List<NodeLoad> loads = slotManager.snapshotOnline();
        assertThat(loads).hasSize(2);

        NodeLoad w1Load = loads.stream().filter(n -> n.node().getNodeCode().equals("w1")).findFirst().orElseThrow();
        NodeLoad w2Load = loads.stream().filter(n -> n.node().getNodeCode().equals("w2")).findFirst().orElseThrow();

        assertThat(w1Load.used()).isEqualTo(3);
        assertThat(w1Load.free()).isEqualTo(2); // 5 - 3
        assertThat(w2Load.used()).isEqualTo(1);
        assertThat(w2Load.free()).isEqualTo(4); // 5 - 1
    }

    @Test
    void hasFreeNormalSlot_detectsExhaustion() {
        insertWorker("w1", 2, 0, "ONLINE");

        // fill all 2 slots
        insertInstance(30005L, "RUNNING", "w1");
        insertInstance(30006L, "RUNNING", "w1");

        assertThat(slotManager.hasFreeNormalSlot()).isFalse();
    }

    @Test
    void nullCapacityWorker_treatedAsZero() {
        insertWorker("w1", null, null, "ONLINE");

        List<NodeLoad> loads = slotManager.availableForNormal();
        assertThat(loads).hasSize(1);
        assertThat(loads.get(0).capacity()).isEqualTo(0);
        assertThat(loads.get(0).free()).isEqualTo(0);
    }
}
