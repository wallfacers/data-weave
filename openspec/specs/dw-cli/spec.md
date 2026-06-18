# dw-cli Specification

## Purpose

定义 `dw` 命令行工具能力：Go 单二进制薄壳，作为运维与 agent 的统一平台入口，调用 master REST 完成任务查询与运维操作，自身不实现权限判断，所有写类操作的裁决与审计统一由平台侧 PolicyEngine 完成。

## Requirements

### Requirement: dw CLI 骨架
项目 SHALL 提供 Go 单二进制 CLI `dw`（仓库 `cli/` 目录，独立构建），M1 子命令：`dw task list|show <id>|instances <taskId>|rerun <instanceId>`、`dw logs cat <instanceId>`；全部子命令支持 `--json` 输出结构化结果，默认输出人类可读表格。

#### Scenario: 查询任务列表
- **WHEN** 执行 `dw task list --json`
- **THEN** 输出任务定义数组（JSON），字段与 master REST 响应同构

#### Scenario: 帮助即能力发现
- **WHEN** 执行 `dw --help` 或 `dw task --help`
- **THEN** 输出完整子命令树与参数说明（agent 的能力发现入口）

### Requirement: CLI 不做本地权限，统一走平台闸门
`dw` SHALL 以配置的 token 调用 master REST，自身不实现权限判断；写类子命令（如 `task rerun`）的裁决与审计 MUST 由 master 侧 PolicyEngine 完成，人手工执行与 agent 经 node_exec 执行过同一闸门、落同一审计。

#### Scenario: 重跑经过裁决
- **WHEN** 运维在 worker 机手工执行 `dw task rerun 100`
- **THEN** master 按 PolicyEngine 裁决（dev 环境 L1 直执行），agent_action 留痕且记录调用来源

#### Scenario: 无 token 拒绝
- **WHEN** 未配置 token 执行写类子命令
- **THEN** master 返回 401，CLI 给出可读错误提示
