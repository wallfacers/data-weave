# policy-engine Specification

## Purpose

定义平台权限闸门能力：PolicyEngine 对每次副作用操作进行 L0–L4 数据驱动分级裁决，管理 L2/L3 审批单生命周期，安全解析 node_exec 命令串，并将既有诊断修复操作纳入统一闸门与审计，确保无绕过路径。

## Requirements

### Requirement: 副作用操作 L0–L4 分级
PolicyEngine SHALL 对每次工具执行裁决等级：L0 只读、L1 可逆例行写、L2 高影响写、L3 不可逆写、L4 禁止。分级规则 MUST 数据驱动（`policy_rules` 表：工具名/命令前缀模式 + 条件 + 等级），并综合资源归属、环境（dev/prod）、数量阈值判定。

#### Scenario: L0/L1 直接执行并审计
- **WHEN** 裁决结果为 L0 或 L1
- **THEN** 立即执行，写入 `agent_action`（approval_status=NONE），L1 额外产生右舷通知

#### Scenario: 资源归属抬升等级
- **WHEN** 操作目标不归属本平台（如 kill 非平台提交的 application）或处于 prod 环境
- **THEN** 即便命令前缀匹配 L1 规则，裁决 MUST 抬升至 L2

#### Scenario: L4 永久拒绝
- **WHEN** 命令匹配 L4 规则
- **THEN** 拒绝执行并以明确错误返回 agent，写审计

### Requirement: 审批单生命周期
L2/L3 操作 SHALL 创建审批单（落 `agent_action`，approval_status=PENDING）并向工具调用方返回 `PENDING_APPROVAL {approvalId, summary, level}`；审批单支持批准、拒绝、超时过期三个终态。L3 批准 MUST 要求二次确认（回输目标对象名）。

#### Scenario: 创建审批单不执行动作
- **WHEN** 裁决为 L2
- **THEN** 工具立即返回 PENDING_APPROVAL，动作不执行，AG-UI 收到 `dataweave.approval` CUSTOM 事件

#### Scenario: 批准后平台侧执行
- **WHEN** 用户对 PENDING 审批单调用 `POST /api/approvals/{id}/approve`
- **THEN** 平台按票据原始参数直接执行（不经 LLM），更新 approval_status=APPROVED、记录审批人与执行结果

#### Scenario: 超时过期
- **WHEN** 审批单超过配置时限未处理
- **THEN** 状态置 EXPIRED，后续 approve 请求被拒绝

### Requirement: 命令串安全解析
对 node_exec 类携带命令串的操作，PolicyEngine SHALL 解析命令：首命令前缀决定基础等级；含重定向（`>` `>>`）、命令分隔（`;` `&&` `||`）、`$()` 或反引号注入时 MUST 升至 L2 及以上。

#### Scenario: 管道过滤不升级
- **WHEN** 命令为只读前缀加管道过滤（如 `dw logs cat 100 | grep -i oom | tail -50`）
- **THEN** 按首命令前缀的等级裁决，不因管道升级

#### Scenario: 重定向与命令分隔升级
- **WHEN** 命令含 `>`、`;`、`&&` 或 `$()`
- **THEN** 裁决至少为 L2（走审批）

### Requirement: 修复操作纳入闸门
既有诊断修复 `applyFix`（RERUN/MIGRATE_NODE/RERUN_MORE_MEMORY/CAP_NODE_WEIGHT）SHALL 经 PolicyEngine 裁决并落 `agent_action`，记录操作者身份；不再存在无审计的直接执行路径。

#### Scenario: 一键修复留痕
- **WHEN** 用户或 agent 触发 applyFix
- **THEN** `agent_action` 记录 action_type、目标实例、操作者、结果；dev 环境 RERUN 类按 L1 直执行
