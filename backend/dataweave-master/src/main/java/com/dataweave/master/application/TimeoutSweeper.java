package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RUNNING 实例超时扫除器（FR-020）。
 *
 * <p>周期性扫描 RUNNING 且 started_at 不为空的实例，若实际运行时长超过其 task_def 配置的
 * {@code timeout_sec}，则经 {@link InstanceStateMachine#casTaskTerminalFromActive}
 * CAS 置 FAILED(TIMEOUT)。
 *
 * <p>外部托管长驻作业（task_def.long_running=true）豁免超时扫除——其存活由外部句柄探测判定。</p>
 * <p><b>扫描频率</b>：默认 30s，保守不激进（超时非紧急，且 CAS 单赢无并发风险）。
 */
@Component
public class TimeoutSweeper {

    private static final System.Logger log = System.getLogger(TimeoutSweeper.class.getName());

    private final JdbcTemplate jdbc;
    private final InstanceStateMachine stateMachine;
    private final EventBus eventBus;

    @Value("${scheduler.timeout-sweep-interval-ms:30000}")
    private long sweepIntervalMs;

    public TimeoutSweeper(JdbcTemplate jdbc, InstanceStateMachine stateMachine,
                          EventBus eventBus) {
        this.jdbc = jdbc;
        this.stateMachine = stateMachine;
        this.eventBus = eventBus;
    }

    /**
     * 周期性扫描 RUNNING 超时实例。
     *
     * <p>SQL 逻辑：JOIN task_def 取 timeout_sec；优先 task_def_version（已发布版本快照），
     * 回退 task_def（TEST 草稿）；仅 timeout_sec 非空且 > 0 时生效。
     * 外部托管长驻作业（long_running）豁免。
     */
    @Scheduled(fixedDelayString = "${scheduler.timeout-sweep-interval-ms:30000}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        // 查询 RUNNING 且 started_at 非空、timeout_sec 配置非空且 >0 的实例。
        // long_running 豁免通过 LEFT JOIN task_def 并在 WHERE 中过滤。
        List<TimeoutCandidate> candidates = jdbc.query(
                """
                SELECT ti.id, ti.started_at, ti.tenant_id,
                       COALESCE(tdv.timeout_sec, td.timeout_sec) AS timeout_sec
                FROM task_instance ti
                LEFT JOIN task_def td ON td.id = ti.task_id
                LEFT JOIN task_def_version tdv ON tdv.task_id = ti.task_id AND tdv.version_no = ti.task_version_no
                WHERE ti.state = 'RUNNING'
                  AND ti.started_at IS NOT NULL
                  AND ti.deleted = 0
                  AND COALESCE(tdv.timeout_sec, td.timeout_sec) IS NOT NULL
                  AND COALESCE(tdv.timeout_sec, td.timeout_sec) > 0
                  AND (td.long_running IS NULL OR td.long_running = FALSE)
                """,
                (rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    LocalDateTime startedAt = rs.getObject("started_at", LocalDateTime.class);
                    int timeoutSec = rs.getInt("timeout_sec");
                    return new TimeoutCandidate(id, startedAt, timeoutSec);
                });

        int swept = 0;
        for (TimeoutCandidate c : candidates) {
            long elapsedSec = java.time.Duration.between(c.startedAt, now).toSeconds();
            if (elapsedSec > c.timeoutSec) {
                boolean ok = stateMachine.casTaskTerminalFromActive(
                        c.id, InstanceStates.FAILED, "TIMEOUT");
                if (ok) {
                    swept++;
                    log.log(System.Logger.Level.INFO,
                            "TimeoutSweeper: 实例 {0} 超时（运行 {1}s > timeout_sec={2}s），已置 FAILED(TIMEOUT)",
                            c.id, elapsedSec, c.timeoutSec);
                }
            }
        }
        if (swept > 0) {
            log.log(System.Logger.Level.INFO, "TimeoutSweeper: 本轮扫除 {0} 个超时实例", swept);
        }
    }

    /** 超时候选：实例 ID + 开始时间 + 配置超时秒数。 */
    private record TimeoutCandidate(UUID id, LocalDateTime startedAt, int timeoutSec) {
    }
}
