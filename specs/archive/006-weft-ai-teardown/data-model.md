# Data Model: 子特性 A —— 服务端 AI 拆除

A 是拆除特性,数据侧只新增一张表 + 删除若干 AI 专属表。

## 新增:`workspace_snapshot`

承载前端 Workspace 的 tab 快照,替代被删 `agent_session.workspace_state`。后端透明存储前端序列化的 JSON blob。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 自增主键 |
| `tenant_id` | BIGINT | 租户隔离(沿用现有隔离列约定) |
| `user_id` | VARCHAR/BIGINT | 快照归属用户(键);一个用户一行,UPSERT 覆盖 |
| `snapshot_json` | VARCHAR(8000) | Workspace tab 状态 JSON(后端不解析) |
| `updated_at` | TIMESTAMP | 最后更新时间 |

- **键**:按 `user_id`(必要时含 `tenant_id`)唯一;写为 UPSERT。
- **读写**:由新建 `WorkspaceSnapshotService` 提供 `get(userId)` / `put(userId, json)`,迁移自 `AgentAuditService.getWorkspaceState/putWorkspaceState`。
- **迁移时序**:先建表 + service + 切前端依赖端点 → 再删 `agent_session`。无需迁移存量快照数据(可丢弃,UI 状态重建成本为零)。

## 删除表(AI 专属)

- `agent_session` / `agent_run` / `agent_step` —— AI 会话/运行/步骤审计(workspace_state 先迁出)
- `finding` —— 主动发现产物
- `task_diagnosis` —— 诊断产物

## 保留表(非 AI,写闸门/治理依赖)

- `agent_action` —— 写闸门对所有副作用操作的留痕 + 审批单载体(由 `GatedActionService` 写,非 AI 专属)
- `policy_rules` —— 数据驱动的策略规则
- 审批相关表 —— `ApprovalService` 依赖

> 注:表名以 `backend/dataweave-api/src/main/resources/schema.sql` 实际为准;WS-BE agent 删除 DDL 前应核对真实表名与外键依赖。
