package com.dataweave.master.application.timing;

import com.dataweave.master.application.TimingStrategy;
import com.dataweave.master.domain.WorkflowDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * CRON 计时策略：用 Spring {@link CronExpression}（6 字段 秒/分/时/日/月/周，原生含秒，无需 cron-utils）。
 * 现状“分钟级”仅因旧 60s 轮询所致；本策略 + 短周期扫描/精确触发后，秒级 cron 直接可用。
 */
@Component
public class CronTimingStrategy implements TimingStrategy {

    private static final Logger log = LoggerFactory.getLogger(CronTimingStrategy.class);

    @Override
    public boolean supports(String scheduleType) {
        return "CRON".equalsIgnoreCase(scheduleType);
    }

    @Override
    public LocalDateTime next(WorkflowDef wf, LocalDateTime base) {
        String expr = wf.getCron();
        if (expr == null || expr.isBlank()) {
            return null;
        }
        try {
            return CronExpression.parse(expr).next(base);
        } catch (Exception e) {
            log.warn("[CronTimingStrategy] 非法 cron '{}' workflow id={}", expr, wf.getId());
            return null;
        }
    }
}
