package com.dataweave.api;

import com.dataweave.master.application.BackfillService;
import com.dataweave.master.application.OpsContracts.BackfillRequest;
import com.dataweave.master.application.OpsContracts.BackfillRunView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * backfill-parallelism-throttle 真实闭环 E2E（all-in-one，H2）：真 {@code BackfillPromoter} bean（订阅 WAKE +
 * @Scheduled sweep）+ 真调度内核 + 真进程内 worker 执行。
 *
 * <p>提交 4 天 × parallelism=2 的补数据：生成侧只放行前 2 个 bizDate（held=0），后 2 个持有（held=1）。若晋升器
 * 失效，持有的 2 个 bizDate 永不被认领 → 永不 SUCCESS → run 永不收敛 → 本测试超时失败。故「收敛到 4/4 SUCCESS
 * 且 heldDates=0」即证明节流放行 + 完成即晋升的整条链路真实工作。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:backfillthrottle;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        // sweep 兜底间隔调小，缩短测试等待（事件驱动为主，此为防漏事件兜底）。
        "backfill.promote.sweep-ms=1000",
        "backfill.promote.initial-ms=500"
})
// ec7868e 移除 worker_nodes 产品 seed 后,MOCK 环境无 HTTP 心跳注册,调度 E2E 须自备 ONLINE worker。
@Sql(scripts = "/test-worker-seed.sql")
class BackfillThrottleE2ETest {

    @Autowired
    BackfillService backfillService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void fourDayBackfill_parallelism2_throttlesThenPromotesToConvergence() throws Exception {
        Long taskId = jdbc.queryForObject(
                "SELECT task_id FROM workflow_node WHERE workflow_id=3 AND task_id IS NOT NULL ORDER BY id LIMIT 1",
                Long.class);

        // 4 天 × 并发 2 → 4 条 BACKFILL 实例（单任务每天一条），前 2 天放行、后 2 天持有。
        BackfillRunView submitted = backfillService.submitBackfill(
                new BackfillRequest("task", taskId, "2026-06-01", "2026-06-04", false, 2));
        UUID runId = submitted.id();
        assertThat(submitted.total()).as("4 天单任务 → 4 条实例").isEqualTo(4);
        assertThat(submitted.parallelism()).isEqualTo(2);

        // 注：初始「2 个 bizDate 被持有」是确定性事实，但进程内 worker 极快、提交返回时往往已晋升跑完，
        // 直接查 held 计数会 race。held 分配正确性由单测 BackfillServiceTest（6.1/6.2）确定性证明，
        // held=1 不被认领由 KernelSchedulingTest（6.7）证明。本 E2E 证明：在 held 真被持有（6.1）且持有期不可认领
        // （6.7）的前提下，整批仍收敛到 4/4 SUCCESS —— 唯一可能就是晋升器把持有的 2 个 bizDate 逐步放行跑完。
        // 若晋升器失效，持有的 2 天永不认领 → 卡在 2/4 → 下面的 await 超时失败。

        // 等待整批收敛：真 promoter 把持有的 bizDate 逐步晋升至全部跑完。
        boolean converged = await(Duration.ofSeconds(40), () -> {
            BackfillRunView v = backfillService.backfillRun(runId).run();
            return !"RUNNING".equals(v.state());
        });
        assertThat(converged).as("补数据批次应在超时内收敛（晋升器把持有 bizDate 全部放行跑完）").isTrue();

        BackfillRunView fin = backfillService.backfillRun(runId).run();
        assertThat(fin.state()).as("4 天均成功 → 批次 SUCCESS").isEqualTo("SUCCESS");
        assertThat(fin.success()).as("4 条实例全部 SUCCESS（证明持有的 2 天确被晋升并执行）").isEqualTo(4);
        assertThat(fin.heldDates()).as("收敛后无待晋升 bizDate").isZero();
        assertThat(fin.activeDates()).as("收敛后无活跃 bizDate").isZero();

        // 兜底校验：DB 中已无任何持有实例。
        Integer heldRemain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_instance WHERE backfill_run_id=? AND COALESCE(backfill_held,0)=1",
                Integer.class, runId);
        assertThat(heldRemain).isZero();
    }

    private boolean await(Duration timeout, java.util.function.BooleanSupplier cond) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(150);
        }
        return cond.getAsBoolean();
    }
}
