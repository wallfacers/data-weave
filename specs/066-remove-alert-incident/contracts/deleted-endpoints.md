# Deleted API Contracts

**Feature**: 066-remove-alert-incident | **Date**: 2026-07-12

本特性为删除清场，**无新增 API 契约**。下列 REST 端点随模块删除而下线。

## 告警中心 `AlertController` → `/api/alert/*`

- `GET /api/alert/rules` — 列告警规则
- `GET /api/alert/events` — 列告警事件
- `GET /api/alert/channels` — 列通知通道
- `GET /api/alert/silences` — 列静默
- `POST /api/alert/events/{id}/ack` — 确认告警事件
- 规则/通道/路由/静默的 CRUD 端点（`AlertController` 全部）

## 数据质量 `QualityController` → `/api/quality/*`

- 由并行工作删除（已下线）

## MCP 工具

- **无删除**。告警/质量走 REST，未注册 MCP 工具（`McpToolRegistry.registerTools()` 无 alert/quality 工具）。`McpToolRegistry` 不动。

## 保留端点（不动）

- `/api/ops/*`（ops overview / metrics / logs SSE / DAG events SSE / ETA）— 运行态观测
- `/api/instances/*` / `/api/workflow-instances/*` — 调度
- `/mcp`（JSON-RPC）— MCP 工具通道
- `/api/projects/*`（pull/push/diff）— 项目同步
- `/api/catalog/*` / `/api/lineage/*` / `/api/authoring-context/*` — 目录与血缘
