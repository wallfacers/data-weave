# Data Model: 工作流实例运维操作

**Date**: 2026-06-26

## 实体变更

### 1. WorkflowInstance (无 DDL 变更)

现有表结构已满足需求。`env` 列已存在于 `schema.sql:464`，无需新增列。

**表**: `workflow_instance`
**新增暴露字段** (查询投影中):

| 字段 | 类型 | 描述 | 变更 |
|------|------|------|------|
| `env` | VARCHAR(8) | PROD / DEV | 已在表中，新增到 DTO 投影 |

### 2. WorkflowInstanceRow (DTO 变更)

**文件**: `OpsContracts.java`
**新增字段**:

```java
// 在 WorkflowInstanceRow record 中新增
String env  // "PROD" | "DEV"
```

### 3. WorkflowInstanceDetail (DTO 变更)

**文件**: `OpsContracts.java` 或 `OpsService.java`
**新增字段**:

```java
// 在 WorkflowInstanceDetail record 中新增
String env  // 从 workflow_instance.env 读取
```

### 4. InstanceRow (DTO 变更)

**文件**: `OpsContracts.java`
**新增字段**:

```java
// 在 InstanceRow record 中新增
String env  // 来自所属 workflow_instance.env
```

### 5. 新增审计记录模式

操作绕过 `GatedActionService` 闸门后，需在 `OpsService` 内直接写入审计记录：

```
AgentAction {
    sessionId:  "ops-direct"        // 固定标记表示运维直接操作
    runId:      UUID.randomUUID()   // 每次操作独立 run
    stepId:     instanceId.toString()
    actionType: "OPS_RERUN_INSTANCE" | "OPS_SET_SUCCESS" | "OPS_KILL_INSTANCE" | ...
    targetId:   instanceId
    outcome:    "EXECUTED"          // 直接执行，无审批
    resultJson: {success: true, ...}
    createdAt:  now()
}
```

## 状态机 (无变更)

现有 `InstanceStateMachine` 和 `InstanceStates` 的状态定义和转换规则完全覆盖规格需求。无新增状态或转换路径。

### 操作允许矩阵 (已实现，供前端参考)

| 操作 | 适用实体 | 允许的源状态 | 目标状态 |
|------|----------|-------------|----------|
| 停止 | 工作流实例 | 非终态 | STOPPED |
| 停止 | 任务实例 | 非终态 | STOPPED |
| 重跑全部 | 工作流实例 | FAILED/STOPPED/SUCCESS | RUNNING (WAITING) |
| 从失败点恢复 | 工作流实例 | FAILED | RUNNING (部分 WAITING) |
| 置成功 | 任务实例 | FAILED/STOPPED/RUNNING/PREEMPTED | SUCCESS |
| 暂停 | 工作流实例 | RUNNING | PAUSED |
| 暂停 | 任务实例 | NOT_RUN | PAUSED |
| 恢复 | 工作流实例 | PAUSED | RUNNING |
| 恢复 | 任务实例 | PAUSED | NOT_RUN |
| 重跑(任务) | 任务实例 | SUCCESS/FAILED/STOPPED | WAITING |

### DEV 环境操作限制

| 操作 | PROD | DEV |
|------|------|-----|
| 停止 | ✅ | ✅ |
| 重跑全部 | ✅ | ❌ |
| 从失败点恢复 | ✅ | ❌ |
| 置成功 | ✅ | ❌ |
| 暂停 | ✅ | ❌ |
| 恢复 | ✅ | ❌ |
| 批量操作 | ✅ | ❌ |
