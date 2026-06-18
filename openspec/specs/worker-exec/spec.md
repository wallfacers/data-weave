# worker-exec Specification

## Purpose

定义 worker 受控命令执行能力：`dataweave-worker` 提供白名单受限、强制超时与输出截断的命令执行端点，配合 master 侧审计落库，并以 `node_exec` MCP 工具契约校验节点在线后转发执行。

## Requirements

### Requirement: 受控命令执行端点
`dataweave-worker` SHALL 提供命令执行端点：仅接受配置化白名单前缀的命令（默认含 `dw `、`tail `、`grep `、`cat `、`df `、`free `、`jstat `），强制超时（默认 60s 可配）与输出截断（默认 64KB 可配），返回 exitCode、stdout/stderr（截断标记含原始长度）。

#### Scenario: 白名单内执行
- **WHEN** master 派发 `df -h` 到某在线 worker
- **THEN** worker 执行并返回退出码与输出

#### Scenario: 白名单外拒绝
- **WHEN** 命令首词不在白名单（如 `rm`、`curl`）
- **THEN** worker 拒绝执行并返回明确错误，不产生子进程

#### Scenario: 超时终止
- **WHEN** 命令执行超过时限
- **THEN** 进程被终止，返回超时标记与已产生的部分输出

### Requirement: 执行审计
worker 端点的每次调用 SHALL 由 master 侧落 `agent_action`（命令、目标节点、发起 step、结果摘要）；worker 自身 MUST 记录执行日志。

#### Scenario: 执行留痕
- **WHEN** node_exec 工具在节点 node-3 执行一条命令
- **THEN** agent_action 记录 target=node-3、完整命令与结果摘要，可由审计回放查到

### Requirement: node_exec 工具契约
MCP 工具 `node_exec` SHALL 接受 `{nodeCode, command}`，由 master 校验节点在线后转发至对应 worker；节点离线或不存在 MUST 返回明确错误。该工具的等级裁决遵循 policy-engine 的命令串安全解析。

#### Scenario: 离线节点报错
- **WHEN** agent 对 OFFLINE 节点调用 node_exec
- **THEN** 返回「节点离线」错误，不入派发队列
