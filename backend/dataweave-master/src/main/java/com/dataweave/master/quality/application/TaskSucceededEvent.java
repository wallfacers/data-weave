package com.dataweave.master.quality.application;

import java.util.UUID;

/**
 * Post-task 门禁事件（research D2.1）：任务实例 CAS 置 SUCCESS 后由
 * {@link com.dataweave.master.application.InstanceStateMachine#casTaskTerminal} publish。
 * {@link TaskSucceededListener @EventListener} 消费后查绑定规则并触发质量门禁。
 *
 * @param taskInstanceId 任务实例 id
 * @param taskId         任务定义 id
 * @param tenantId       租户
 */
public record TaskSucceededEvent(UUID taskInstanceId, Long taskId, Long tenantId) {
}
