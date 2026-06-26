# Data Model: DAG 节点详情侧面板

**Created**: 2026-06-26

## Entities

### 1. DagNodeDto（扩展）

**Location**: `backend/dataweave-master/.../application/WorkflowService.java`
**Change**: 新增 `taskVersionNo` 字段

| Field | Type | Description |
|-------|------|-------------|
| nodeKey | String | 节点在 DAG 中的唯一标识 |
| nodeType | String | `"TASK"` 或 `"VIRTUAL"` |
| taskId | Long | 关联任务 ID（VIRTUAL 节点为 null） |
| taskVersionNo | Integer | **新增** — 发布时冻结的任务版本号（VIRTUAL 节点为 null） |
| name | String | 节点显示名称 |
| posX | Integer | 节点在画布上的 X 坐标 |
| posY | Integer | 节点在画布上的 Y 坐标 |
| taskStatus | String | 运行时状态（`published-dag` 响应中为 null；实例查询时有值） |

**来源**: `WorkflowDagSnapshot.Node` 在 `readPublishedDag()` 中反序列化后映射。`taskVersionNo` 原本存在于快照数据中但被丢弃，现在补传。

### 2. NodeTaskDetail（新增响应 DTO）

**Location**: `backend/dataweave-master/.../application/` （`OpsService` 或 `WorkflowService` 中的 record）
**Purpose**: `GET /api/ops/workflows/{workflowId}/nodes/{nodeKey}/detail` 的响应体

| Field | Type | Description |
|-------|------|-------------|
| nodeKey | String | 节点标识 |
| taskId | Long | 任务 ID |
| taskName | String | 任务名称（发布时） |
| taskType | String | 任务类型标识（如 `"SQL"`, `"PYTHON"`, `"SHELL"`, `"DATA_SYNC"`） |
| versionNo | Integer | 发布版本号 |
| content | String? | 执行代码/脚本内容（可为 null，取决于任务类型） |
| paramsJson | String? | 配置参数 JSON 字符串（可为 null） |
| datasourceId | Long? | 数据源 ID |
| targetDatasourceId | Long? | 目标数据源 ID |
| timeoutSec | Integer | 超时秒数 |
| retryMax | Integer | 最大重试次数 |
| publishedAt | String | 发布时间（ISO 8601） |
| hasCode | Boolean | 该任务类型是否包含代码（由 `type` 决定） |
| deleted | Boolean | 关联任务是否已被删除（降级标记） |

**来源**: `TaskDefVersion` 实体。`hasCode` 由任务类型元数据推导。

### 3. NodeDetailState（前端状态）

**Location**: `frontend/lib/workspace/node-detail-store.ts`
**Purpose**: Zustand store 管理侧面板状态

| Field | Type | Description |
|-------|------|-------------|
| selectedNode | {nodeKey, taskId, taskVersionNo}? | 当前选中的节点（null = 面板关闭） |
| detail | NodeTaskDetail? | 已获取的节点详情数据 |
| loadState | "idle" \| "loading" \| "loaded" \| "error" | 加载状态 |
| errorMessage | String? | 错误信息（loadState="error" 时） |

**Actions**:
- `selectNode(nodeKey, taskId, taskVersionNo)` — 选中节点，触发详情加载
- `deselectNode()` — 取消选中，关闭面板
- `setDetail(detail)` — 加载成功后设置详情
- `setError(message)` — 加载失败时设置错误

### 4. 前端 Panel 宽度持久化

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| localStorage `dw.dagViewer.panelWidth` | number | `dialogWidth / 4` | 面板像素宽度，在拖拽释放时写入 |

## State Transitions

### 面板加载状态机

```
idle ──selectNode()──▶ loading ──fetch success──▶ loaded ──selectNode(new)──▶ loading
                         │                            │
                         └──fetch error──▶ error      └──deselectNode()──▶ idle
                                              │
                                              └──retry──▶ loading

loaded ──deselectNode()──▶ idle
error  ──deselectNode()──▶ idle
```

### 约束
- `selectNode()` 在 `loading` 状态下可以再次调用（切换节点），前一个请求的结果被丢弃。
- `deselectNode()` 可在任意状态调用，重置为 `idle`。
- VIRTUAL 节点不触发状态转换（`selectNode` 在 VIRTUAL 节点上为 no-op）。

## Validation Rules

- `nodeKey` 必须存在于对应 workflowId 的发布快照中，否则返回 404。
- `taskId` 必须不为 null（仅 TASK 节点可查询详情）。
- `taskVersionNo` 用于从 `TaskDefVersion` 查询；若对应版本已被物理删除，返回降级响应（`content: null` + `deleted: true`）。
