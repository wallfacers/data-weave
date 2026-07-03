# Data Model: 042 产品面收缩

无数据库/服务端实体变更（FR-007）。本特性的"数据模型"是前端视图注册的纯数据结构，变更如下。

## ViewType（视图全集）

18 → 14。删除成员：`marketplace`、`reports`、`service`、`integration`。

保留全集：`ops`、`workflow-canvas`、`freshness`、`metrics`、`fleet`、`lineage`、`catalog`、`quality`、`datasources`、`alerts`、`event-center`、`settings`、`instance-log`、`workflow-instance-detail`。

## VIEW_META（视图元数据）

| 删除项 | 原属性 | 连带影响 |
|---|---|---|
| `marketplace` | `requirePermission: "metric:manage"` | 前端不再有视图引用该权限码；权限码本身留在服务端种子数据（不动） |
| `reports` | `defaultPinned: true` | **PINNED_VIEWS 由 3 缩为 2**（freshness、metrics）；首开默认标签随之变化 |
| `service` / `integration` | 无特殊属性 | placeholder 工厂失去全部消费者 → 随删 |

## NAV_GROUPS（导航分组）

| 分组 | 变更 |
|---|---|
| `assets` | `[marketplace, datasources, integration, service]` → `[datasources]`（组保留） |
| `analytics` | 仅含 `reports` → **整组删除**（含 `leftNav.groups.analytics` i18n 键） |
| 其余分组 | 不变 |

**不变量（保持成立，测试守护）**：入口视图 ∪ 上下文详情视图 = ViewType 全集，且入口无重复。

## WorkspaceSnapshot（浏览器侧快照）

**格式不变**。旧快照中 `view` 字段可能出现已删除值；恢复路径既有守卫（`isKnownView` 过滤 + 至少回到 Pinned 底座 + 激活态回退）自动降级——本特性只补测试用例，不改结构（research D5）。

## 状态转移

无新增状态机。唯一行为性变化：`open(<removed-view>)` 与 `restore(含 removed-view 快照)` 从"打开视图"变为既有的"忽略/丢弃"分支。
