# agent-audit Specification

## Purpose

定义 Agent 全链路审计能力：以审计四表持久化对话、运行、工具调用与副作用操作，覆盖 mock 与 workhorse 两种模式，并支持按运行回放完整步骤序列作为回归评测集的数据来源。

## Requirements

### Requirement: 审计四表
系统 SHALL 持久化 agent 全链路：`agent_session`（对话 ⇄ workhorse 会话映射 + mode）、`agent_run`（一次用户触发的运行）、`agent_step`（每次工具调用，tool_use_id/工具名/输入/输出引用/截断标记/耗时）、`agent_action`（副作用操作 + 分级 + 审批生命周期）。DDL MUST 兼容 PostgreSQL 与 H2，遵循项目公共审计列约定。

#### Scenario: 工具调用全留痕
- **WHEN** workhorse 模式下 agent 完成一次含 3 个工具调用的运行
- **THEN** `agent_run` 1 行、`agent_step` 3 行，step 的 tool_use_id 与 workhorse 事件中的 id 一致

#### Scenario: mock 模式同样留痕
- **WHEN** `agent.mode=mock` 下 IntentRouter 处理一条消息
- **THEN** 同样产生 agent_run 记录（step 按意图分支粒度），审计不因模式缺失

### Requirement: workhorse 事件落库映射
桥接层 SHALL 消费 workhorse 会话 SSE 并落库：`tool_call_start`（建 step、存 input）、`tool_call_done`（补 output 引用与截断信息）、`permission_resolved`（记录 decision 与 source：rule/default/prompt/timeout）。

#### Scenario: 权限决议可审计
- **WHEN** 某工具调用经 workhorse 规则放行或超时拒绝
- **THEN** 对应 step 记录 decision 与 source，回放时可区分"谁批的/规则放行/超时"

### Requirement: 运行可回放
系统 SHALL 提供按 run 查询完整步骤序列（含输入输出）的接口，作为回归评测集的数据来源。

#### Scenario: 按 run 回放
- **WHEN** 调用回放接口并传入 run id
- **THEN** 返回时间有序的全部 step 与 action，输出超长部分可经引用取回全文
