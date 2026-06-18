package com.dataweave.master.application;

import com.dataweave.master.domain.SlaBaseline;
import com.dataweave.master.domain.SlaBaselineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * SLA 基线服务（design D14，task 5.3）。
 *
 * <p>在工作流实例成功完成时记录就绪时刻；基于历史数据计算基线；
 * 检测破线（就绪时间显著晚于历史基线）并标记。workflow_instance 恒为正式运行
 *（试跑 triggerTestRun 只建孤立 task_instance、不建 workflow_instance），无需排除 TEST。
 *
 * <h3>基线算法</h3>
 * v1：近 {@code N} 次成功就绪时间的中位数（默认 N=7）。
 * 若历史不足 3 次，不判定破线（基线不稳）。
 * 破线阈值：晚于基线超过 {@code thresholdMinutes} 分钟（默认 60）。
 */
@Service
public class SlaService {

    private static final Logger log = LoggerFactory.getLogger(SlaService.class);

    private final SlaBaselineRepository repository;
    private final JdbcTemplate jdbc;

    private final int baselineWindow;
    private final int thresholdMinutes;

    @Autowired
    public SlaService(SlaBaselineRepository repository, JdbcTemplate jdbc) {
        this(repository, jdbc, 7, 60);
    }

    /** 可测试构造：指定窗口大小与破线阈值。 */
    public SlaService(SlaBaselineRepository repository, JdbcTemplate jdbc,
                      int baselineWindow, int thresholdMinutes) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.baselineWindow = baselineWindow;
        this.thresholdMinutes = thresholdMinutes;
    }

    /**
     * 工作流实例成功完成时调用：记录就绪时刻，计算基线，判定破线。
     */
    public void recordCompletion(UUID workflowInstanceId) {
        try {
            // workflow_instance 恒为正式运行：试跑 triggerTestRun 只建孤立 task_instance
            //（workflow_instance_id=null、run_mode=TEST），不创建 workflow_instance，故 SLA 无需排除 TEST。
            var row = jdbc.queryForMap(
                    "SELECT wi.workflow_id, wi.biz_date, wi.finished_at "
                            + "FROM workflow_instance wi WHERE wi.id = ? AND wi.deleted = 0",
                    workflowInstanceId);
            if (row.isEmpty()) {
                return;
            }
            Long workflowId = ((Number) row.get("WORKFLOW_ID")).longValue();
            String bizDate = (String) row.get("BIZ_DATE");
            // JDBC 把 TIMESTAMP 列读成 java.sql.Timestamp（H2/PG 均如此），不能直接强转 LocalDateTime。
            Object finishedAtRaw = row.get("FINISHED_AT");
            LocalDateTime finishedAt = finishedAtRaw == null ? LocalDateTime.now()
                    : finishedAtRaw instanceof LocalDateTime ldt ? ldt
                    : ((java.sql.Timestamp) finishedAtRaw).toLocalDateTime();

            // 幂等：已存在记录则更新
            SlaBaseline baseline = repository.findByWorkflowIdAndBizDate(workflowId, bizDate)
                    .orElseGet(() -> {
                        SlaBaseline b = new SlaBaseline();
                        b.setWorkflowId(workflowId);
                        b.setBizDate(bizDate);
                        b.setCreatedAt(LocalDateTime.now());
                        return b;
                    });

            baseline.setWorkflowInstanceId(workflowInstanceId);
            baseline.setReadyAt(finishedAt);
            baseline.setUpdatedAt(LocalDateTime.now());

            // 计算历史基线
            List<LocalDateTime> history = repository.recentReadyTimes(workflowId, baselineWindow);
            if (!history.isEmpty()) {
                // 排除本次记录（如果已存在）
                history.removeIf(t -> t.equals(finishedAt));
            }
            if (history.size() >= 3) {
                LocalDateTime histBaseline = median(history);
                baseline.setBaselineReadyAt(histBaseline);
                long lateMinutes = Duration.between(histBaseline, finishedAt).toMinutes();
                if (lateMinutes > thresholdMinutes) {
                    baseline.setBreached(1);
                    baseline.setBreachMinutes((int) lateMinutes);
                    log.warn("[SLA] 破线！workflow={} bizDate={} 基线={} 实际={} 晚{}min",
                            workflowId, bizDate, histBaseline, finishedAt, lateMinutes);
                } else {
                    baseline.setBreached(0);
                    baseline.setBreachMinutes(null);
                }
            }

            repository.save(baseline);
        } catch (Exception e) {
            log.warn("[SLA] 记录就绪时刻失败（非阻塞）：{}", e.getMessage());
        }
    }

    /** 最近 N 次成功就绪时间的中位数。 */
    static LocalDateTime median(List<LocalDateTime> times) {
        if (times.isEmpty()) {
            return null;
        }
        List<LocalDateTime> sorted = new ArrayList<>(times);
        sorted.sort(Comparator.naturalOrder());
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        // 偶数 → 取两个中间值的平均
        long epoch1 = sorted.get(mid - 1).toEpochSecond(java.time.ZoneOffset.UTC);
        long epoch2 = sorted.get(mid).toEpochSecond(java.time.ZoneOffset.UTC);
        return LocalDateTime.ofEpochSecond((epoch1 + epoch2) / 2, 0, java.time.ZoneOffset.UTC);
    }

    /** 最近破线记录（供看板/告警消费）。 */
    public List<SlaBaseline> recentBreaches(int limit) {
        return repository.recentBreaches(limit);
    }
}
