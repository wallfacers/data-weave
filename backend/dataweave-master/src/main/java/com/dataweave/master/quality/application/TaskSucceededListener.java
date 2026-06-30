package com.dataweave.master.quality.application;

import com.dataweave.master.quality.domain.QualityRule;
import com.dataweave.master.quality.domain.QualityRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Post-task 门禁监听器（research D2.1 / 接缝 B）：消费 {@link TaskSucceededEvent}，
 * 查该任务 id 绑定的启用 POST_TASK 质量规则并触发 {@link QualityCheckRunner#run(List, ...)}。
 *
 * <p>用 {@link TransactionalEventListener#AFTER_COMMIT} 保证状态机事务已提交、下游可见
 * （守死锁防御不变量③：事务内只落状态，事务外副作用）。
 *
 * <p>监听失败不传播异常（日志 + 吞掉），不影响调度闭环。
 */
@Component
public class TaskSucceededListener {

    private static final Logger log = LoggerFactory.getLogger(TaskSucceededListener.class);

    private final QualityRuleRepository ruleRepository;
    private final QualityCheckRunner runner;

    public TaskSucceededListener(QualityRuleRepository ruleRepository, QualityCheckRunner runner) {
        this.ruleRepository = ruleRepository;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskSucceeded(TaskSucceededEvent event) {
        try {
            List<QualityRule> rules = ruleRepository.findByTenantIdAndBoundTaskIdAndEnabledAndDeleted(
                    event.tenantId(), event.taskId(), 1, 0);
            if (rules.isEmpty()) {
                return;
            }
            log.info("[QualityGate] POST_TASK 门禁触发 taskId={} rules={}", event.taskId(), rules.size());
            runner.run(rules, "POST_TASK", event.taskInstanceId(), event.tenantId());
        } catch (Exception e) {
            log.error("[QualityGate] POST_TASK 门禁执行异常 taskId={}: {}", event.taskId(), e.getMessage(), e);
        }
    }
}
