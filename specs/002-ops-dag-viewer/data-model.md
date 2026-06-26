# Data Model: 运维任务流 DAG 查看器

**Feature**: 002-ops-dag-viewer | **Date**: 2026-06-26

## 概述

本功能**不新增数据库表**，仅读取现有 `workflow_def_version.dag_snapshot_json` 列。前端复用已有 `DagView` 类型。

## 现有实体

### WorkflowDefVersion (发布快照)

表名: `workflow_def_version`

| 字段 | 类型 | 描述 |
|------|------|------|
| `id` | BIGINT PK | 自增主键 |
| `workflow_id` | BIGINT FK → `workflow_def.id` | 所属任务流 |
| `version_no` | INT | 版本序号，从 1 递增 |
| `name` | VARCHAR | 发布时的任务流名称 |
| `schedule_type` | VARCHAR | MANUAL / CRON |
| `cron` | VARCHAR | cron 表达式 |
| **`dag_snapshot_json`** | TEXT (JSON) | **核心字段** — DAG 快照，见下方结构 |
| `remark` | VARCHAR | 发布备注 |
| `published_by` | BIGINT | 发布人 ID |
| `published_at` | TIMESTAMP | 发布时间 |
| `created_at` | TIMESTAMP | 记录创建时间 |

#### dag_snapshot_json 结构

```json
{
  "nodes": [
    {
      "nodeKey": "node-1",
      "nodeType": "TASK",
      "taskId": 42,
      "taskVersionNo": 5,
      "name": "清洗-用户表",
      "posX": 100,
      "posY": 200
    },
    {
      "nodeKey": "node-2",
      "nodeType": "VIRTUAL",
      "taskId": null,
      "taskVersionNo": null,
      "name": "入口",
      "posX": 0,
      "posY": 200
    }
  ],
  "edges": [
    {
      "fromNodeKey": "node-1",
      "toNodeKey": "node-2",
      "strength": "STRONG"
    }
  ]
}
```

**Node 字段**:
- `nodeKey` — 节点稳定标识符
- `nodeType` — `"TASK"` (绑定任务) 或 `"VIRTUAL"` (零负载锚点)
- `taskId` — 关联 `task_def.id` (VIRTUAL 节点为 null)
- `taskVersionNo` — **发布时锁定的任务版本号**
- `name` — 节点显示名称
- `posX`, `posY` — 画布坐标 (布局快照)

**Edge 字段**:
- `fromNodeKey`, `toNodeKey` — 引用节点 `nodeKey`
- `strength` — `"STRONG"` (强依赖) 或 `"WEAK"` (弱依赖)

### Java 实体

```java
// WorkflowDagSnapshot.java (已存在)
public record WorkflowDagSnapshot(
    List<Node> nodes,
    List<Edge> edges
) {
    public record Node(String nodeKey, String nodeType, Long taskId,
                       Integer taskVersionNo, String name, Integer posX, Integer posY) {}
    public record Edge(String fromNodeKey, String toNodeKey, String strength) {}
}
```

## API 响应类型

### DagView (已存在，前后端共享)

```java
// Java 侧 (WorkflowService)
public record DagView(Long workflowId, Long version, Integer hasDraftChange,
                      String status, List<DagNodeDto> nodes, List<DagEdgeDto> edges) {}
```

```typescript
// TypeScript 侧 (lib/types.ts)
export interface DagView {
  workflowId: number
  version: number
  hasDraftChange: number
  status: string
  nodes: DagNode[]
  edges: DagEdge[]
}
```

### 新端点响应

`GET /api/workflows/{id}/published-dag` → `ApiResponse<DagView>`

其中 `DagView` 的字段含义：
- `workflowId` — 任务流 ID
- `version` — 发布版本号 (`versionNo`)
- `hasDraftChange` — 当前是否已产生草稿改动 (从 `WorkflowDef.hasDraftChange` 获取)
- `status` — `"ONLINE"` (总是 ONLINE，因为 DRAFT 工作流无发布版本)
- `nodes` — 从 `dagSnapshotJson` 反序列化的节点列表
- `edges` — 从 `dagSnapshotJson` 反序列化的边列表

## 数据流

```
┌─────────────────────┐     GET /api/workflows/{id}/published-dag     ┌──────────────┐
│  periodic-workflows │ ────────────────────────────────────────────→ │ WorkflowCtrl │
│  -panel.tsx         │                                               │   .readPub   │
│  manual-workflows   │                                               │   lishedDag  │
│  -panel.tsx         │                                               └──────┬───────┘
│                     │                                                      │
│  onClick "查看DAG"  │ ←──── ApiResponse<DagView> ──────────────────┐      │
│  → setSelected(w)   │                                               │      │
│  → render Dlg       │                                               │      ▼
└─────────┬───────────┘                                        WorkflowService
          │                                                    .readPublishedDag()
          ▼                                                          │
   DagViewerDialog                                                   │
   ├─ fetch published-dag                                            ▼
   ├─ toFlow() → ReactFlow nodes/edges              WorkflowDefVersionRepository
   ├─ ReactFlow (readOnly)                          .findByWorkflowIdAndVersionNo()
   ├─ TaskNode / VirtualNode                              │
   └─ empty / error states                                ▼
                                               deserialize dagSnapshotJson
                                                      │
                                                      ▼
                                               WorkflowDagSnapshot
                                               → DagView DTO
```

## 状态转换

本功能不涉及状态变更。涉及的状态判断：

| 条件 | 行为 |
|------|------|
| `WorkflowDef.status === "ONLINE"` | 显示"查看 DAG"按钮，可点击打开弹框 |
| `WorkflowDef.status === "DRAFT"` | 按钮不可见，或置灰无操作 |
| `WorkflowDef.currentVersionNo === null` | 等同于 DRAFT，无发布版本 |
| `dagSnapshotJson` 反序列化后 nodes 为空 | 弹框展示 empty state |
| API 调用抛出异常 | 弹框展示 error state + retry |
