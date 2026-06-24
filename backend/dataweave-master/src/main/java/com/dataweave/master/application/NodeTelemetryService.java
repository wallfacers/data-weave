package com.dataweave.master.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 节点遥测真采集（live-telemetry L1）：诊断证据中的并发争抢数与失败 history 由实时 SQL 聚合得出，
 * 不再信任 worker 上报或硬编码。
 */
@Service
public class NodeTelemetryService {

    private final JdbcTemplate jdbc;

    public NodeTelemetryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** master 端按 worker_node_code 聚合：该节点当前 DISPATCHED/RUNNING 的实例数（真实并发争抢）。 */
    public int concurrentTasks(String workerNodeCode) {
        if (workerNodeCode == null) {
            return 0;
        }
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_instance "
                        + "WHERE worker_node_code=? AND state IN ('DISPATCHED','RUNNING') AND deleted=0",
                Integer.class, workerNodeCode);
        return n != null ? n : 0;
    }

    /** 近 7 天该 task_id × 该 worker_node_code 的 FAILED 实例数（真实失败 history）。 */
    public int failureCount7d(Long taskId, String workerNodeCode) {
        if (taskId == null || workerNodeCode == null) {
            return 0;
        }
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_instance "
                        + "WHERE task_id=? AND worker_node_code=? AND state='FAILED' AND deleted=0 "
                        + "AND finished_at >= ?",
                Integer.class, taskId, workerNodeCode, LocalDateTime.now().minusDays(7));
        return n != null ? n : 0;
    }
}
