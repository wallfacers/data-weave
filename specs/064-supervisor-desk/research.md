# Research: 监督席

**Feature**: 064-supervisor-desk
**Date**: 2026-07-11

## R1: 现有后端自动开单/愈合机制

**Decision**: 扩展现有实现，不重写。

**Rationale**: 043 已实现完整的 Incident 管线——`IncidentSignalListener` 监听 AlertSignal → `IncidentService.openOrAttach()` 开单/附着；`IncidentHealListener` 监听 TaskSucceededEvent/WorkflowSucceededEvent → `IncidentService.healByTask/healByWorkflowInstance` 愈合；`IncidentSweeper` 定时心跳检查愈合节点工单 + 过期 RESOLVED → CLOSED。这些内核成熟稳定，符合 Constitution V (Reuse the Kernel)。

**Alternatives considered**: 新建独立 SignalProcessor（被 Q2 否决——选最小后端 hook）。

### 需要修改的点

1. **签名（signature）规范化**：当前 `IncidentSignalListener` 将 `failureReason` 规范化为 `failureClass`（TIMEOUT/EXIT_NONZERO/WORKER_RESTART/WORKER_LOST/UNKNOWN），丢失了原始失败原因精度。Q3 要求精确指纹 `eventType + sourceRefId + failureReason`，需改为直接使用原始 `failureReason` 字符串（如 `EXIT_CODE_-1`）。

2. **愈合条件映射**：当前 `healByTask(taskId)` 按 `source_kind='TASK' AND source_ref_id=taskId` 全量愈合所有 OPEN/MITIGATING 工单。Q4 要求精确指纹匹配——只愈合开单时预存映射的工单。需在 `incident` 表新增 `heal_by_type`/`heal_by_ref_id` 列，开单时写入（如 TASK_FAILED → 由 TASK_SUCCESS + taskId=100 愈合），愈合时按此精确匹配。

## R2: 时间线抽屉的 UI 架构

**Decision**: 使用 `Dialog` + `DetailPanelShell` 模式（与 DAG 弹窗一致），不复用 `FlowCanvasWithPanel`（时间线无画布）。

**Rationale**: DetailPanelShell 已是领域无关壳（title/close/loading/error/hasData/headerExtra/scrollBody），直接复用零改动。时间线面板不需要 DAG 画布，因此无需 `FlowCanvasWithPanel` 的左右分栏——直接在 Dialog 内渲染 `DetailPanelShell` 即可。`LineageDetailPanel` 证明了 DetailPanelShell 可以脱离 `FlowCanvasWithPanel` 独立使用（在 lineage-view 中通过 `renderPanel` 注入）。

**Alternatives considered**:
- 复用 `FlowCanvasWithPanel` → 画布区域闲置浪费，增加复杂度
- 保持现有 `fixed right-0` div → 被用户明确拒绝（"不喜欢这样的抽屉"）
- 新建 Sheet 组件 → 过度设计；Dialog + DetailPanelShell 已满足需求且风格一致

### 组件层次

```
IncidentTimelineDialog (NEW)
  └── Dialog (shadcn/ui, base-ui)
       └── DetailPanelShell
            ├── title: 工单标题
            ├── headerExtra: 可选筛选（事件类型）
            ├── scrollBody: true (DwScroll 垂直滚动)
            └── children: 时间线条目列表
                 ├── 每条: 图标 + 类型标签 + 操作者 + 时间戳 + JSON payload
                 └── 空态: 图标 + "暂无时间线记录"
```

## R3: 信号流面板的数据来源

**Decision**: 复用现有 `GET /api/events` 端点，新增 `?incidentOnly=true` 查询参数过滤仅工单关联信号。

**Rationale**: Q1 确定信号流 Tab 仅展示与工单关联的信号（非全部 HealthEvent）。当前 `GET /api/events` 支持 `type`/`severity`/`refKind`/`refId` 筛选，但不区分"已生成工单"和"未生成工单"。新增 `incidentOnly` 参数在后端通过 JOIN `incident` 表过滤（`health_event.ref_id = incident.source_ref_id`），前端无需感知工单 ID 映射。

**Alternatives considered**:
- 新建 `/api/incidents/{id}/signals` → 粒度太细（需要先知道工单 ID）
- 前端双请求后内存 join → 性能差，数据量大时不适用

## R4: 设计系统组件映射

**Decision**: 完整映射表如下，所有选择与 DESIGN.md 公共组件目录一致。

| UI 需求 | 规范组件 | 路径 | 关键配置 |
|---|---|---|---|
| 页面内 Tab 切换 | `Tabs` + `TabsList` + `TabsTrigger` + `TabsContent` | `components/ui/tabs.tsx` | `size="md"`, 下划线式 |
| 信号/工单卡片容器 | `Card` + `CardContent` | `components/ui/card.tsx` | `size="default"`, `--card-spacing` |
| 可滚动区域 | `DwScroll` | `components/ui/dw-scroll.tsx` | `direction="vertical"` |
| 严重度标记 | `Badge` | `components/ui/badge.tsx` | `variant="destructive"|"warning"|"info"|"success"` |
| 状态标记 | `Badge` | `components/ui/badge.tsx` | `variant="secondary"|"outline"` |
| 加载态 | `LoadingState` | `components/workspace/shared/loading-state.tsx` | `variant="centered"|"overlay"` |
| 刷新入口 | `ViewRefreshControl` | `components/workspace/views/view-refresh-control.tsx` | 15s auto + 手动 |
| 类型/严重度筛选 | `DropdownSelect` | `components/ui/select.tsx` | portal + clearable |
| 时间线抽屉壳 | `DetailPanelShell` | `components/workspace/detail-panel-shell.tsx` | `scrollBody=true` |
| 弹窗基底 | `Dialog` | `components/ui/dialog.tsx` | base-ui |

## R5: Schema 版本与迁移

**Decision**: Schema 从当前 0.10.0 → 0.11.0，新增两列 + 索引。

**Rationale**: 语义化版本 MINOR bump——新增可选列（向后兼容）、不改既有列语义或约束。H2 和 PG 均支持 `ALTER TABLE ADD COLUMN IF NOT EXISTS`。不使用迁移脚本——`schema.sql` 是单一权威 DDL，直接在其中添加列。

### 新增 DDL

```sql
ALTER TABLE incident ADD COLUMN IF NOT EXISTS heal_by_type VARCHAR(32) NULL;
ALTER TABLE incident ADD COLUMN IF NOT EXISTS heal_by_ref_id VARCHAR(128) NULL;
CREATE INDEX IF NOT EXISTS idx_incident_heal ON incident(tenant_id, heal_by_type, heal_by_ref_id);
```
