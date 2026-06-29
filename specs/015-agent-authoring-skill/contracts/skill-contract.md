# Contract: Weft 任务创作 Skill

## SKILL.md frontmatter（契约）

```yaml
---
name: weft-task-authoring
description: >
  Author, edit, run, and push Weft tasks/task-flows. Use when the developer wants to
  create or modify a Weft task or flow, run a task locally, diff, or push to the server.
allowed-tools: Bash, Read, Write, Edit, Grep
---
```

- **触发契约**: 上述 `description` 在用户表达"创建/编辑 Weft 任务或任务流、本地跑/push"意图时 MUST 触发自动加载（FR-006）。
- **权限契约**: `allowed-tools` 至少覆盖运行 `dw`（Bash）与读写本地任务文件（Read/Write/Edit/Grep）（FR-013）。

## 正文必含小节（契约）

| 小节 | 内容 | 对应 FR |
|------|------|---------|
| 文件契约 | project.yaml / _folder.yaml / *.task.yaml / *.flow.yaml / 脚本体独立文件 / tags.yaml 结构语义 | FR-007 |
| params + 占位符 | 取值约定 + `{{...}}` 语法语义 | FR-008 |
| datasource | 可用逻辑名"从何处查得"（本地配置 / dw 命令） | FR-009 |
| flow 一致性 | edges.from/to MUST 指向真实 node key | FR-010 |
| dev-loop | pull → 写/改 → `dw run` → `dw diff` → `dw push` → `dw run --test` | FR-001 |
| GateResult 三态 | EXECUTED / PENDING_APPROVAL / REJECTED；含删除/高危→挂起≠失败 | FR-011 |

## 支持文件（契约）

- `file-contract.md`：文件契约一页速查。
- `examples/`：可仿写最小任务 + 任务流 + datasources.local.yaml 样例，MUST 被 `dw run`/`dw push` 接受（FR-012）。

## 一致性 lint（契约）

- Skill 正文出现的每个 `dw <subcommand>` 与 `--flag` MUST 存在于 `dw` 实际命令表（FR-027）。校验失败即 CI fail。
