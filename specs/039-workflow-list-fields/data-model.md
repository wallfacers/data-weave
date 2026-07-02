# Data Model: 周期/手动任务流列表字段增强

**Feature**: 039-workflow-list-fields | **Date**: 2026-07-02

> 本 feature **不改数据库表**。下面记录涉及的既有字段（只读）+ 应用层 DTO/类型扩展（后端 record + 前端 type）。

## 1. 数据库表 `workflow_def`（既有，不改 — schema.sql:332）

本 feature 启用的既有字段：

| 字段 | 类型 | 说明 | 本 feature 用途 |
| --- | --- | --- | --- |
| `name` | VARCHAR(256) NOT NULL | 任务流名称 | 列（既有） |
| `description` | VARCHAR(512) | 描述 | **新增**名称副标题 |
| `cron` | VARCHAR(128) | Cron 表达式 | 周期列（既有） |
| `status` | VARCHAR(32) | ONLINE/DRAFT | 状态列（既有） |
| `current_version_no` | INTEGER | 当前版本号 | 版本列（既有） |
| `has_draft_change` | SMALLINT | 草稿改动 | 版本徽标（既有） |
| `last_fire_time` | TIMESTAMP | 上次触发 | 上次运行列（既有） |
| **`next_trigger_time`** | TIMESTAMP | 下次计划触发（单值持久化） | **新增**周期「下次触发时间」列 |
| **`priority`** | INTEGER DEFAULT 5 | 0=最高，9=最低 | **新增**「优先级」列 + 筛选 + 排序 |
| `catalog_node_id` | BIGINT | 类目 | DTO 已投影（本 feature 不展示） |

**无新增列、无 schema_version 变更。**

## 2. 后端 DTO 扩展 — `OpsContracts.java`

### `WorkflowListRow`（增 1 字段）

```java
public record WorkflowListRow(
    Long id, String name, String description, String cron, String status,
    Integer currentVersionNo, Integer hasDraftChange, String lastFireTime,
    Integer priority, Integer timeoutSec, String updatedAt, Long updatedBy,
    Long catalogNodeId, String recentTriggerResult,
    String nextTriggerTime   // ← 新增：ISO 字符串，null 透传（手动流 / 未回填）
) {}
```

### `WorkflowQuery`（增 3 字段）

```java
public record WorkflowQuery(
    String scheduleType, String keyword, Integer hasDraftChange,
    String recentResult, Long catalogNodeId, Long createdBy,
    Long projectId, int page, int size,
    String priorityTier,   // ← 新增："high"(0-2) | "normal"(3-9) | null
    String sortField,      // ← 新增：白名单 "priority" | null
    String sortDir         // ← 新增："asc" | "desc"，默认 asc
) {}
```

## 3. 后端 SQL 行为变更 — `OpsService#queryWorkflows` (137-204)

- **SELECT** 增 `wd.next_trigger_time`；RowMapper 增 `nextTriggerTime` 映射（`LocalDateTime`→`toString`，null 透传）。
- **WHERE** 增 priorityTier：`high`→`AND wd.priority BETWEEN 0 AND 2`；`normal`→`AND wd.priority BETWEEN 3 AND 9`；null→不加。
- **ORDER BY**：`sortField=priority` 时 `ORDER BY wd.priority <dir> NULLS LAST, wd.id LIMIT ? OFFSET ?`；否则维持 `ORDER BY wd.id LIMIT ? OFFSET ?`。
- **白名单**：`sortField` 仅接受 `"priority"`，其余一律退化为默认排序（防注入）。

## 4. 前端类型扩展 — `lib/data-table.ts`

### `ColumnDef`（增可选 sortable）

```ts
export interface ColumnDef<T> {
  // ...既有字段不变...
  sortable?: boolean  // ← 新增：标记列头可点击排序（缺省 false，向后兼容）
}
```

### `FetchQuery`（增可选 sort）

```ts
export interface FetchQuery {
  filters: FilterValues
  page: number
  size: number
  sort?: { field: string; dir: "asc" | "desc" }  // ← 新增
}
```

### `toQueryParams` 增 sort 序列化：`sort=field:dir`（field 已在列白名单内）。

## 5. 前端 `WorkflowRow`（增 1 字段 — `periodic-workflows-panel.tsx`）

```ts
export interface WorkflowRow {
  // ...既有字段不变...
  nextTriggerTime: string | null  // ← 新增
}
```

## 6. 新建 `frontend/lib/relative-time.ts`

纯函数 `relativeNextTrigger(iso, now)` → `{ key, values } | null`（见 research.md D5）。无持久化、无状态、无 DOM 依赖 → vitest 可直接覆盖。

## 验证规则（来自 spec FR，测试须断言）

- `next_trigger_time` NULL（首轮回填前 / 手动流）→ 列显示 `—`。
- `priority` NULL（历史数据）→ 显示 `—`；排序 `NULLS LAST` 置末。
- `priority ≤ 2` → 数字 + 高优徽标。
- `priorityTier=high` → 仅返回 priority 0–2。
- `description` 空 → 名称列不预留副标题空行。
