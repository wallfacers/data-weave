## ADDED Requirements

### Requirement: 运维异常巡检与举手台事件

系统 SHALL 在后端巡检失败/超时实例,并通过 AG-UI `CUSTOM(dataweave.ops.alert)` 事件推送举手台卡片;事件 payload 含 kind/severity/title/detail/instanceIds 与可选 suggestedAction。

#### Scenario: 失败实例触发告警事件
- **WHEN** 实例进入 FAILED 终态
- **THEN** 后端评估并发出 `dataweave.ops.alert` 事件 `{ kind:"INSTANCE_FAILED", severity, instanceIds, suggestedAction:{ op:"rerun" } }`

#### Scenario: 事件去重
- **WHEN** 同一实例短时间内重复触发
- **THEN** 同一异常不重复刷卡片(按实例 + kind 去重)

### Requirement: 运维写动作经闸门闭环

系统 SHALL 使举手台建议动作与所有运维写操作(batch/backfill/freeze/set-success)统一构造 `ActionRequest → GatedActionService.submit → PolicyEngine` 裁决,并留 `agent_action` 审计,无旁路。

#### Scenario: 批准建议走闸门
- **WHEN** 用户在举手台点「批准」执行某建议动作
- **THEN** 经 `GatedActionService` 裁决:L0/L1 直接执行,L2/L3 创建审批返回 PENDING_APPROVAL,L4 拒绝,均落 `agent_action`

#### Scenario: 未配策略默认待批
- **WHEN** 某写动作未配 `policy_rules`
- **THEN** 按默认等级裁决(不直接执行高风险写),前端按 outcome 分流

### Requirement: 运维 MCP 工具

系统 SHALL 在 `McpToolRegistry` 注册运维工具,使 workhorse 真脑可驱动同一套运维动作:查询工具直查 domain service,写工具经 `GatedActionService` 闸门。

#### Scenario: 真脑查询失败实例
- **WHEN** 真脑经 MCP 调用查询失败实例工具
- **THEN** 返回失败实例列表(直查 domain service,无副作用)

#### Scenario: 真脑发起补数据经闸门
- **WHEN** 真脑经 MCP 调用补数据工具
- **THEN** 该写操作经 `GatedActionService` 裁决并留审计,与人工发起同闸门、同审计轨迹

### Requirement: mock 模式运维意图路由

系统 SHALL 在 `agent.mode=mock` 下,于 `IntentRouter` 增加运维意图分支(查失败实例、发起重跑/补数据建议),与真脑模式经同一 `AguiEvents` 出口发出同构事件。

#### Scenario: mock 模式问失败实例
- **WHEN** mock 模式下用户问「今天有哪些任务失败了」
- **THEN** IntentRouter 路由到运维分支,经 AG-UI 文本 + 可选 `dataweave.ops.alert`/`dataweave.ui.open` 事件回应,与真脑模式同构
