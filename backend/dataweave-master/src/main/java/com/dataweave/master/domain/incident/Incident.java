package com.dataweave.master.domain.incident;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 事故（Incident）：一次故障响应的一等载体，人机共同的工作单位（069）。
 * 不变量：① 同一任务至多一个未收口事故（openKey 唯一，收口=null）；
 * ② state=RESOLVED ⇔ closedAt≠null ⇔ openKey=null；③ autoActionCount 只增不减；
 * ④ 只读观察调度，绝不反向锁 task_instance 行（守调度锁序红线）。
 */
public record Incident(
        UUID id,
        long tenantId,
        long projectId,
        long taskDefId,
        String taskDefName,
        UUID firstInstanceId,
        UUID latestInstanceId,
        int instanceCount,
        String triggerSource,      // CRON | MANUAL | STREAMING
        String classification,     // 见 IncidentClassifications；null=未诊断
        String confidence,         // HIGH | MEDIUM | LOW
        String state,              // 见 IncidentStates
        Long openKey,              // 开着=taskDefId；收口=null
        int autoActionCount,
        String summary,
        String suggestion,
        String closeKind,          // AUTO | HUMAN_ASSISTED | MANUAL
        LocalDateTime openedAt,
        LocalDateTime closedAt,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
