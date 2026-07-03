package com.dataweave.master.application.incident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Incident 定时清扫器（043）：两个周期任务，多 master 靠 CAS 幂等（范式 A，零协调，不加锁不建 guard 表）。
 *
 * <ol>
 *   <li>RESOLVED 超 7 天 → 集合 CAS 转 CLOSED（active_key = NULL）。</li>
 *   <li>NODE 类工单心跳恢复检查 → CAS RESOLVED。</li>
 * </ol>
 *
 * <p>NODE 愈合：查询所有 OPEN/MITIGATING 的 NODE 工单的 source_ref_id（即 nodeCode），
 * 判断 worker_nodes 表中对应 node 心跳是否新鲜 → CAS RESOLVED。
 */
@Component
public class IncidentSweeper {

    private static final Logger log = LoggerFactory.getLogger(IncidentSweeper.class);

    private final IncidentService incidentService;
    private final JdbcTemplate jdbc;

    public IncidentSweeper(IncidentService incidentService, JdbcTemplate jdbc) {
        this.incidentService = incidentService;
        this.jdbc = jdbc;
    }

    /** ① RESOLVED 超 7 天 → CLOSED（集合 CAS，多 master 幂等）。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void sweepStaleResolved() {
        try {
            incidentService.sweepStaleResolved();
        } catch (Exception e) {
            log.error("[IncidentSweeper] stale resolved sweep failed", e);
        }
    }

    /** ② NODE 类工单心跳恢复 → RESOLVED。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void healNodesByHeartbeat() {
        try {
            // 取所有活跃 NODE 工单的 nodeCode
            List<String> nodeCodes = jdbc.query(
                    "SELECT DISTINCT source_ref_id FROM incident " +
                    "WHERE source_kind = 'NODE' AND state IN ('OPEN','MITIGATING') AND deleted = 0",
                    (rs, n) -> rs.getString("source_ref_id"));

            for (String nodeCode : nodeCodes) {
                try {
                    // 检查 worker_nodes 心跳新鲜度（last_heartbeat_at 在最近 60s 内 = 已恢复）
                    List<Long> tenantIds = jdbc.query(
                            "SELECT tenant_id FROM incident " +
                            "WHERE source_kind = 'NODE' AND source_ref_id = ? AND state IN ('OPEN','MITIGATING') AND deleted = 0",
                            (rs, n2) -> rs.getLong("tenant_id"), nodeCode);

                    for (Long tenantId : tenantIds) {
                        // 查 worker_nodes 心跳
                        Integer online = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM worker_nodes WHERE code = ? " +
                                "AND last_heartbeat_at IS NOT NULL " +
                                "AND last_heartbeat_at > ?",
                                Integer.class, nodeCode, LocalDateTime.now().minusMinutes(2));
                        if (online != null && online > 0) {
                            int healed = incidentService.healNodesByCode(nodeCode, tenantId);
                            if (healed > 0) {
                                log.info("[IncidentSweeper] NODE healed: code={} tenant={} count={}",
                                        nodeCode, tenantId, healed);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("[IncidentSweeper] NODE heal check failed for code={}", nodeCode, e);
                }
            }
        } catch (Exception e) {
            log.error("[IncidentSweeper] NODE heartbeat heal sweep failed", e);
        }
    }
}
