## ADDED Requirements

### Requirement: 统一 Finding 模型

系统 SHALL 定义统一的发现模型 `Finding`，作为所有巡检器与下游消费方的唯一契约，持久化到 `finding` 表。`Finding` SHALL 至少包含：`source`（发现来源，如 TASK_FAILURE/DATA_QUALITY/SLA）、`severity`（INFO/WARN/CRITICAL）、`targetType`+`targetId`（被发现对象）、`title`、`rootCause`、`evidenceJson`（任意键值证据）、`actionsJson`（可执行修复项列表 `{key,label,actionType}`）、`status`（OPEN/ANNOUNCED/RESOLVED）、`announced`、`createdAt`。

#### Scenario: 任意来源产出同构 Finding

- **WHEN** 任一巡检器发现一个问题
- **THEN** 产出一行结构一致的 `Finding`，下游举手台/播报/修复无需区分来源即可处理

### Requirement: Inspector SPI 可插拔

系统 SHALL 提供 `Inspector` SPI（`source()` + `inspect(): List<Finding>`）。新增一类发现能力 SHALL 只需实现一个 `Inspector` 并注册为 Spring Bean，**不修改任何下游**（调度、落库、举手台、播报、修复均不变）。本次 SHALL 至少首发 `TaskFailureInspector`。

#### Scenario: 接入新巡检器零改下游

- **WHEN** 新增一个实现 `Inspector` 的 Bean
- **THEN** 调度器自动纳入其巡检，其产出的 Finding 经统一链路落库与展示，无需改动下游代码

#### Scenario: 失败巡检器复用既有诊断

- **WHEN** `TaskFailureInspector.inspect()` 发现一个未诊断的 FAILED 实例
- **THEN** 内部调用 `DiagnosisService.diagnoseInstance(instanceId)`，将产出的 `TaskDiagnosis` 映射为一行 `Finding`（source=TASK_FAILURE），不重写诊断逻辑

### Requirement: 巡检调度与去重

系统 SHALL 以 `InspectorScheduler` 周期性（`@Scheduled` 定时兜底）遍历所有 `Inspector` 执行巡检；失败事件可加速触发但定时兜底 SHALL 始终保证不漏。落库前 SHALL 以 `(source,targetType,targetId)` 去重——已存在 OPEN 或 ANNOUNCED 的同键 Finding 不重复创建。

#### Scenario: 定时兜底发现失败

- **WHEN** 存在一个 FAILED 且无对应 Finding 的实例
- **THEN** 在下一个巡检周期内系统为其创建一条 OPEN 的 Finding

#### Scenario: 同问题不重复举手

- **WHEN** 同一 `(source,targetType,targetId)` 已有 OPEN/ANNOUNCED Finding
- **THEN** 巡检不再为其新建 Finding

### Requirement: Findings 查询与一键修复 API

系统 SHALL 提供 `GET /api/findings` 返回当前 OPEN/ANNOUNCED 的 `Finding[]`，供举手台加载。系统 SHALL 提供 `POST /api/findings/{id}/apply`（body `{actionKey}`）执行该 Finding 选定的修复项，**经现有 `GatedActionService` 闸门**（L0/L1 直执行，L2/L3 返回 PENDING_APPROVAL，L4 拒绝），返回 `{executed,message,outcome,newInstanceId?}`；成功修复后对应 Finding 置为 RESOLVED。

#### Scenario: 列出待办发现

- **WHEN** 前端请求 `GET /api/findings`
- **THEN** 返回所有未解决（OPEN/ANNOUNCED）的 Finding 列表

#### Scenario: 修复经闸门

- **WHEN** 用户对某 Finding 调用 `POST /api/findings/{id}/apply`
- **THEN** 动作构造 `ActionRequest` 经 PolicyEngine 裁决并留痕；被裁为审批/拒绝时 `executed=false` 且 outcome 反映裁决，绝无绕过闸门的执行路径

#### Scenario: 修复成功后收尾

- **WHEN** 修复动作被闸门直接执行成功
- **THEN** 对应 Finding 置为 RESOLVED，不再出现在举手台
