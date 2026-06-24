package com.dataweave.master.application;

import java.util.UUID;

/**
 * 任务实例转入 FAILED 终态时发布的进程内事件，用于加速主动发现
 * （{@link InspectorScheduler} 异步监听后立刻巡检，无需等定时兜底）。
 *
 * <p>仅作加速：即便异步监听因事务可见性竞态漏掉本次，下一轮 {@code @Scheduled} 定时兜底仍会补上。
 */
public record TaskInstanceFailedEvent(UUID taskInstanceId) {
}
