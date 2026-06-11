package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 软抢占（design D6，task 2.6）：高优先级实例无槽可用且存在更低优先级的 {@code preemptible} 运行实例时，
 * 终止该实例（→ PREEMPTED）并回炉 WAITING 重排，<b>不消耗 attempt</b>。非 preemptible 不被强制终止。
 *
 * <p>抢占与自然完成的竞态由 CAS 裁决：{@code casPreempt} 与 worker 回报的 {@code casTaskTerminal} 互斥，
 * 后到者 CAS 失败即放弃，不产生状态覆盖。
 */
@Service
public class PreemptionService {

    private static final Logger log = LoggerFactory.getLogger(PreemptionService.class);

    private final JdbcTemplate jdbc;
    private final SlotManager slotManager;
    private final InstanceStateMachine stateMachine;
    private final EventBus eventBus;

    public PreemptionService(JdbcTemplate jdbc, SlotManager slotManager,
                             InstanceStateMachine stateMachine, EventBus eventBus) {
        this.jdbc = jdbc;
        this.slotManager = slotManager;
        this.stateMachine = stateMachine;
        this.eventBus = eventBus;
    }

    /**
     * 若存在「可运行的高优 WAITING 实例」却无空槽，且有更低优先级的 preemptible 运行实例，则抢占一个，
     * 腾出槽位并发布唤醒。成功抢占返回 true。
     */
    public boolean preemptOneForWaitingHighPriority() {
        if (slotManager.hasFreeNormalSlot()) {
            return false;  // 有空槽无需抢占（work-conserving）
        }
        Integer demandPriority = minRunnableWaitingPriority();
        if (demandPriority == null) {
            return false;  // 无可运行的待调度需求
        }
        Victim victim = pickVictim(demandPriority);
        if (victim == null) {
            return false;  // 无可被抢占的更低优先级 preemptible 实例
        }
        if (stateMachine.casPreempt(victim.id, victim.state)
                && stateMachine.casRequeue(victim.id, InstanceStates.PREEMPTED)) {
            log.info("[Preemption] 抢占实例 {}（优先级 {}）让位于优先级 {} 的待调度任务",
                    victim.id, victim.priority, demandPriority);
            eventBus.publish(InstanceStates.WAKE_CHANNEL, "preempt");
            return true;
        }
        return false;  // 竞态：实例已自然完成，放弃
    }

    /** 可运行（上游就绪）的 WAITING NORMAL 实例的最高优先级（数值最小）。 */
    private Integer minRunnableWaitingPriority() {
        List<Integer> r = jdbc.query(
                "SELECT MIN(wi.priority) AS p FROM task_instance ti "
                        + "JOIN workflow_instance wi ON wi.id=ti.workflow_instance_id "
                        + "WHERE ti.state='WAITING' AND ti.run_mode='NORMAL' AND ti.deleted=0 "
                        + "AND wi.state NOT IN ('PAUSED','STOPPED') "
                        + "AND NOT EXISTS (SELECT 1 FROM workflow_edge e "
                        + "   JOIN task_instance pred ON pred.workflow_instance_id=ti.workflow_instance_id "
                        + "        AND pred.workflow_node_id=e.from_node_id AND pred.deleted=0 "
                        + "   WHERE e.to_node_id=ti.workflow_node_id AND e.deleted=0 AND pred.state<>'SUCCESS')",
                (rs, n) -> (Integer) rs.getObject("p"));
        return r.isEmpty() ? null : r.get(0);
    }

    /** 选一个优先级低于需求（数值更大）的 preemptible 运行实例作为牺牲者（最低优先级者优先）。 */
    private Victim pickVictim(int demandPriority) {
        List<Victim> v = jdbc.query(
                "SELECT ti.id, ti.state, wi.priority AS p FROM task_instance ti "
                        + "JOIN workflow_instance wi ON wi.id=ti.workflow_instance_id "
                        + "JOIN workflow_def wd ON wd.id=wi.workflow_id "
                        + "WHERE ti.state IN ('RUNNING','DISPATCHED') AND ti.run_mode='NORMAL' AND ti.deleted=0 "
                        + "AND wd.preemptible=1 AND wi.priority > ? "
                        + "ORDER BY wi.priority DESC LIMIT 1",
                (rs, n) -> {
                    Victim x = new Victim();
                    x.id = rs.getObject("id", UUID.class);
                    x.state = rs.getString("state");
                    x.priority = (Integer) rs.getObject("p");
                    return x;
                }, demandPriority);
        return v.isEmpty() ? null : v.get(0);
    }

    private static final class Victim {
        UUID id;
        String state;
        Integer priority;
    }
}
