# 实时任务面板 5 项增强

**日期**: 2026-07-11 | **分支**: `064-supervisor-desk` | **关联**: 062 实时任务运维

## 概述

在 062 已实现的 StreamingTasksPanel 基础上做 5 项 UI/UX 增强，使其与 PeriodicInstancesPanel 交互模式对齐。

## 任务分解

### T1 — 任务列拆分

当前 `colTask` 列堆叠显示任务名 + 实例 ID 后缀。拆为两列：

- **实例 ID** (`instanceId`)：居前列，`…{id.slice(-8)}` + Copy01Icon 点击复制（参照 PeriodicInstancesPanel ID 列模式）
- **任务名称** (`taskName`)：居后列，`truncate font-medium`

### T2 — 操作列

当前操作列已覆盖 checkpoint 重跑（ResumeCheckpointDialog）+ 优雅停止（stopWithSavepoint）+ 强制终止（kill）。无需新增按钮。

### T3 — 日志滚动条

`InstanceDetailSidePanel` 日志 Tab 当前直接使用 `OverlayScrollbarsComponent`，需改为 `DwScroll` 统一项目规范。

### T4 — ID 和状态搜索

参照 `PeriodicInstancesPanel`，给 `DataTable` 传入 `filters: FilterDef[]`：
- `stateIn`：状态下拉多选（RUNNING/DISPATCHED/WAITING/STOPPED/SUSPENDED/FAILED）
- `keyword`：搜索框（按实例 ID / 任务名称模糊匹配）

后端 `GET /api/ops/streaming-tasks` 需支持 `stateIn` 和 `keyword` 查询参数。

### T5 — 最近检查点查看弹框

在 `lastCheckpoint` 列已有 Badge 旁加"查看"按钮 → 居中 `Dialog` 展示检查点详情：
- 序号、状态、路径、大小、完成时间、是否可恢复/已过期

## 涉及文件

| 文件 | 改动 |
|---|---|
| `frontend/.../ops/streaming-tasks-panel.tsx` | T1 列拆分 + T4 filters + T5 按钮/弹框 |
| `frontend/.../ops/instance-detail-side-panel.tsx` | T3 日志区 DwScroll |
| `backend/.../OpsController.java` | T4 后端筛选参数 |
| `backend/.../OpsService.java` | T4 后端筛选逻辑 |
| `frontend/messages/zh-CN.json` | i18n 新键 |
| `frontend/messages/en-US.json` | i18n parity |

## 设计系统合规

- Badge: `success`/`warning`/`destructive`/`outline` 语义变体
- DwScroll: 所有可滚动区域
- Dialog: shadcn/ui Dialog 居中弹框
- 筛选: DataTableToolbar FilterDef 模式
