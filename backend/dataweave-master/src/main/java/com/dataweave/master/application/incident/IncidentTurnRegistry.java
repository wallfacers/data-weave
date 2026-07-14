package com.dataweave.master.application.incident;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/**
 * 070 事故对话轮次的打断句柄注册表（进程内，不持久化）。
 *
 * <p>每个事故同一时刻至多一次在途 Agent 输出轮次：{@link IncidentConversationService#respond} 开始时
 * {@link #begin} 注册一个取消标志、结束（自然完成/异常/被打断）时 {@link #end} 清除；
 * {@link LlmChatClient} 读循环以该标志为取消检查点。打断请求经统一写闸门后 {@link #cancel} 置位标志。
 *
 * <p>竞态（打断 vs 自然完成）以「先落库者胜」收敛：标志置位后读循环若已退出则不再二次落库，
 * 前端始终以最终落库消息为真相。进程重启丢失在途轮次本就终止，cancel 幂等语义覆盖。
 */
@Component
public class IncidentTurnRegistry {

    private final ConcurrentHashMap<UUID, AtomicBoolean> turns = new ConcurrentHashMap<>();

    /** 轮次开始：注册并返回该轮次的取消标志（后者覆盖前者——串行度低，冲突窗口极小）。 */
    public AtomicBoolean begin(UUID incidentId) {
        AtomicBoolean flag = new AtomicBoolean(false);
        turns.put(incidentId, flag);
        return flag;
    }

    /** 轮次结束：仅当仍是本轮次的标志时移除（避免误删后续轮次）。 */
    public void end(UUID incidentId, AtomicBoolean flag) {
        turns.remove(incidentId, flag);
    }

    /** 请求时是否有在途轮次。 */
    public boolean isActive(UUID incidentId) {
        return turns.containsKey(incidentId);
    }

    /** 置位取消标志；返回是否确有在途轮次被打断（无则幂等返回 false）。 */
    public boolean cancel(UUID incidentId) {
        AtomicBoolean flag = turns.get(incidentId);
        if (flag == null) return false;
        flag.set(true);
        return true;
    }
}
