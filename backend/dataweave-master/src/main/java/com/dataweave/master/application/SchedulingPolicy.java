package com.dataweave.master.application;

import com.dataweave.master.domain.WorkerNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 调度策略接缝（design D5）：把「打分 / 选节点」与正确性内核（CAS 状态机 / 幂等下发 / 租约）分离。
 *
 * <p>策略只产出分数与选择，<b>不碰状态</b>；替换实现不影响正确性保证——算错最坏是调度不优，
 * 不会丢任务/死锁/重复执行。未来 AI 智能调度从此接管（经 MCP 调优先级或提供 hint）。
 */
public interface SchedulingPolicy {

    /**
     * 有效优先级（<b>数值越小越优先</b>，0 最高）。默认实现 = 声明优先级 − 等待时长 aging（防饥饿）。
     */
    int effectivePriority(Candidate c, LocalDateTime now);

    /**
     * 从有空槽的候选节点中择一（默认 least-loaded：选剩余槽位最多者）。无候选返回空。
     */
    Optional<NodeLoad> place(Candidate c, List<NodeLoad> candidates);

    /**
     * 待调度实例的调度视图。
     *
     * @param instanceId       任务实例 id
     * @param declaredPriority 声明优先级（继承自 workflow，0 最高 9 最低）
     * @param waitingSince     进入 WAITING 的时刻（aging 基准）
     * @param test             是否 TEST 试跑（天然高优）
     */
    record Candidate(UUID instanceId, int declaredPriority, LocalDateTime waitingSince, boolean test) {
    }

    /**
     * 节点负载视图。
     *
     * @param node     节点
     * @param used     已占用槽数
     * @param capacity 总槽数
     */
    record NodeLoad(WorkerNode node, int used, int capacity) {
        public int free() {
            return capacity - used;
        }
    }
}
