package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 补数据 bizDate 粒度并发晋升器（backfill-parallelism-throttle，design D3）。
 *
 * <p>语义：{@code parallelism=N} 表示「同时最多 N 个 bizDate 链放行（{@code backfill_held=0}）」。生成侧把超配额的
 * bizDate 整批置 {@code held=1}；本组件在某活跃 bizDate 链全部进入终态后，晋升下一个 held bizDate（整批 1→0）补足配额。
 *
 * <p>触发：① 订阅 {@link InstanceStates#WAKE_CHANNEL}（实例完成/释放槽位即发 WAKE）低延迟晋升；② {@code @Scheduled}
 * 周期 sweep 兜底防漏事件。二者调同一幂等方法。
 *
 * <p>严格 N 保证与死锁不变量：晋升以 <b>per-run {@code SELECT … FOR UPDATE} 行锁</b>串行化（防并发 wake 各自晋升不同
 * bizDate 把并发推过 N）。该行锁只锁 {@code backfill_run}（认领路径从不锁此表，无环、无交叉等待）；晋升经 CAS-style
 * UPDATE；发 WAKE 副作用在事务提交之后（invariant ④）。认领路径四条不变量原样不变。
 */
@Service
public class BackfillPromoter {

    private static final Logger log = LoggerFactory.getLogger(BackfillPromoter.class);

    private final JdbcTemplate jdbc;
    private final EventBus eventBus;
    private final TransactionTemplate txTemplate;

    public BackfillPromoter(JdbcTemplate jdbc, EventBus eventBus, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.eventBus = eventBus;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /** 订阅 WAKE：每次实例完成/槽位释放即尝试补足配额（低延迟）。 */
    @PostConstruct
    void subscribeWake() {
        eventBus.subscribe(InstanceStates.WAKE_CHANNEL, msg -> {
            try {
                promoteEligible();
            } catch (RuntimeException e) {
                log.warn("[BackfillPromoter] WAKE 晋升异常：{}", e.toString());
            }
        });
    }

    /** 周期 sweep 兜底（默认 15s）：防 WAKE 漏达导致活跃配额空闲却有 held bizDate 永久卡死。 */
    @Scheduled(fixedDelayString = "${backfill.promote.sweep-ms:15000}", initialDelayString = "${backfill.promote.initial-ms:20000}")
    public void sweep() {
        try {
            promoteEligible();
        } catch (RuntimeException e) {
            log.warn("[BackfillPromoter] sweep 晋升异常：{}", e.toString());
        }
    }

    /**
     * 遍历进行中的补数据批次，逐个在 per-run 行锁内补足配额。幂等：可被 WAKE/sweep 并发重复调用。
     * 任一 run 发生晋升则在事务外补发一次 WAKE 触发认领。
     */
    public void promoteEligible() {
        List<UUID> runIds = jdbc.query(
                "SELECT id FROM backfill_run WHERE state='RUNNING' AND deleted=0",
                (rs, n) -> rs.getObject("id", UUID.class));
        boolean anyPromoted = false;
        for (UUID runId : runIds) {
            anyPromoted |= promoteRun(runId);
        }
        if (anyPromoted) {
            // invariant ④：副作用（唤醒认领）在晋升事务提交之后。
            eventBus.publish(InstanceStates.WAKE_CHANNEL, "backfill-promote");
        }
    }

    /** 单 run 晋升：per-run 行锁 → 重算 active/held → 补足配额 → 收敛终态。返回是否发生晋升。 */
    private boolean promoteRun(UUID runId) {
        Boolean promoted = txTemplate.execute(status -> {
            // per-run 行锁：串行化同一 run 的并发晋升（防超发，保证严格 N）。
            Integer parallelism = jdbc.query(
                    "SELECT parallelism FROM backfill_run WHERE id=? AND state='RUNNING' AND deleted=0 FOR UPDATE",
                    rs -> rs.next() ? rs.getInt("parallelism") : null, runId);
            if (parallelism == null) {
                return false; // 已被他人终结 / 不存在
            }
            int par = Math.max(1, parallelism);
            int active = countActiveDates(runId);
            int held = countHeldDates(runId);
            boolean did = false;
            while (active < par && held > 0) {
                String nextDate = jdbc.query(
                        "SELECT MIN(biz_date) AS d FROM task_instance "
                                + "WHERE backfill_run_id=? AND deleted=0 AND COALESCE(backfill_held,0)=1",
                        rs -> rs.next() ? rs.getString("d") : null, runId);
                if (nextDate == null) {
                    break;
                }
                int n = jdbc.update(
                        "UPDATE task_instance SET backfill_held=0, updated_at=? "
                                + "WHERE backfill_run_id=? AND biz_date=? AND COALESCE(backfill_held,0)=1",
                        LocalDateTime.now(), runId, nextDate);
                if (n == 0) {
                    break; // 兜底：行锁已串行，理论不达
                }
                log.info("[BackfillPromoter] run={} 晋升 bizDate={}（放行 {} 个实例）", runId, nextDate, n);
                active++;
                held--;
                did = true;
            }
            // 收敛：无活跃、无持有 → 全部终态，终结 run.state 使其退出扫描。
            if (active == 0 && held == 0) {
                finalizeRunState(runId);
            }
            return did;
        });
        return Boolean.TRUE.equals(promoted);
    }

    /** 放行（held=0）且未全部终态的 bizDate 数。 */
    private int countActiveDates(UUID runId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT biz_date) FROM task_instance "
                        + "WHERE backfill_run_id=? AND deleted=0 AND COALESCE(backfill_held,0)=0 "
                        + "AND state NOT IN ('SUCCESS','FAILED')",
                Integer.class, runId);
        return c == null ? 0 : c;
    }

    /** 持有（held=1）待晋升的 bizDate 数。 */
    private int countHeldDates(UUID runId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT biz_date) FROM task_instance "
                        + "WHERE backfill_run_id=? AND deleted=0 AND COALESCE(backfill_held,0)=1",
                Integer.class, runId);
        return c == null ? 0 : c;
    }

    /** 全终态时按子实例聚合派生 SUCCESS/FAILED/PARTIAL 并落库（停止被 promoteEligible 继续扫描）。 */
    private void finalizeRunState(UUID runId) {
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_instance WHERE backfill_run_id=? AND deleted=0", Integer.class, runId);
        Integer success = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_instance WHERE backfill_run_id=? AND deleted=0 AND state='SUCCESS'",
                Integer.class, runId);
        Integer failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_instance WHERE backfill_run_id=? AND deleted=0 AND state='FAILED'",
                Integer.class, runId);
        int t = total == null ? 0 : total;
        int s = success == null ? 0 : success;
        int f = failed == null ? 0 : failed;
        if (t == 0) {
            return; // 无子实例（异常空批次），不强行终结
        }
        String derived = f == 0 ? "SUCCESS" : (s == 0 ? "FAILED" : "PARTIAL");
        jdbc.update("UPDATE backfill_run SET state=?, updated_at=? WHERE id=? AND state='RUNNING'",
                derived, LocalDateTime.now(), runId);
        log.info("[BackfillPromoter] run={} 收敛终态 {}（total={} success={} failed={}）", runId, derived, t, s, f);
    }
}
