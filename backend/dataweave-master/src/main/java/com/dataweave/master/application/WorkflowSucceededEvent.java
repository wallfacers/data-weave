package com.dataweave.master.application;

import java.util.UUID;

/**
 * 工作流实例成功完成事件（043 incident 自动愈合信号源）。
 *
 * <p>镜像 {@link com.dataweave.master.quality.application.TaskSucceededEvent}（TaskSucceededEvent
 * 在 quality.application 包，但 WorkflowSucceededEvent 是 master 级事件——incident 域无 quality 依赖）。
 *
 * <p>由 {@link WorkerReportService} workflow SUCCESS 分支发布（吞异常，最佳尽力），
 * 由 {@link IncidentHealListener} 消费。
 *
 * @param workflowInstanceId 工作流实例 id
 * @param workflowId         工作流定义 id
 * @param tenantId           租户
 */
public record WorkflowSucceededEvent(UUID workflowInstanceId, Long workflowId, Long tenantId) {
}
