# Data Model: 受影响数据资产（v2 — 真实行号）

**Feature**: 011-weft-cleanup | **Date**: 2026-06-28 | **基准树**: `e568c38`（真 main）

净化任务不新建数据模型，但删除涉及若干遗留数据资产。本文件记录受影响的表 / seed / 存储（真实行号），并划清"可删"与"不可动"边界。

> v1 行号基于过时树 6177ffa，已作废；本 v2 基于 `git reset --hard main` 后的真 main（e568c38）。

## 删除的表（`schema.sql`）

| 表 | 行号 | 批次 | 说明 |
|----|------|------|------|
| `agent_chat_file` | `schema.sql` CREATE `:911-923` + DROP `:56`（095814f 加）（无 seed） | 1 | AG-UI 聊天附件遗留（宪法 IV + No Legacy Migration） |
| `notification_channels` | `schema.sql:732`（DROP :38） | 6 | alert 空骨架 |
| `alert_rules` | `schema.sql:747`（DROP :15） | 6 | alert 空骨架 |

## 删除的 seed（`data.sql`，FR-011 连带）

| seed | 行号 | 批次 |
|------|------|------|
| `notification_channels` INSERT | `data.sql:557-558` | 6 |
| `alert_rules` INSERT | `data.sql:560-561` | 6 |
| `ALTER TABLE ... RESTART WITH 100` | `data.sql:647, :648` | 6 |

**保留**: `data.sql:452, :454` 的 `data_quality.alerts`（业务 demo SQL 字符串，非 DDL 表，与 alert 模块无关）。

## ⚠️ freeze_task 无 policy_rule seed（修正 v1）

v1 假设"freeze_task 的 policy_rule 若有则删"。**真 main 事实**：`data.sql` 中 `grep FREEZE_TASK|FREEZE_NODE` **零命中**——这两个写动作的 `policy_rules` seed 本就不存在（种子漂移，走 PolicyEngine 默认 L2）。故退役 `freeze_task` 时**无 policy_rule 连带要删**。

（对比：`project_push` 有 seed `data.sql:607/608`；`create_task` 有"已移除"注释 `data.sql:597`。）

## JwtAuthFilter `/agui`

- 白名单条目 `:39`（`PREFIX_WHITELIST` :38-44）+ javadoc `:24` + 连带注释（`CorsConfig:13`、`SseNoBufferingWebFilter:15`、`OpsController:43`）
- **揭红 7762422 的 CORS 预检修复（import `:8` + `:54-59`）保留**——与 /agui 无关

## 不受影响（边界澄清）

| 资产 | 守护原则 | 处置 |
|------|---------|------|
| 治理数据（`task_def`/`workflow_def`/版本快照/`agent_action` audit） | 宪法 II + V | **不动** |
| scheduling kernel 表（claim/lock/cron guard） | 宪法 V | **不动** |
| observability 数据（metrics/run logs/DAG） | 宪法 IV 红线 | **不动** |
| `DefaultPlatformActionExecutor`（PROJECT_PUSH→`projectSyncService.push`） | 宪法 III executor 复用 | **不动**（`createAndOnline` 删除不影响它，因 E 已解耦） |

## 实现期核实

行号基于 `e568c38`。main 是 moving target（收尾 AI 持续推进），若 main 再前进需复查——**非微移，是可能的结构性变化**（如 E 重塑就整段重写了 McpToolRegistry/DefaultPlatformActionExecutor）。
