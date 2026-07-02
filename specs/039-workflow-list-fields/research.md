# Research: 周期/手动任务流列表字段增强

**Feature**: 039-workflow-list-fields | **Date**: 2026-07-02 | **Spec**: [spec.md](spec.md)

> Phase 0 产出。所有 [NEEDS CLARIFICATION] 已在 `/speckit-clarify` 解决（见 spec `## Clarifications`，4 轮）。本文记录实现路径的关键技术决策与接缝验证，每条含 Decision / Rationale / Alternatives。

## D1: schema 要不要改？

**Decision**: **不改 schema，不升 schema_version。**

**Rationale**: 本 feature 三个字段 `next_trigger_time`(schema.sql:346)、`priority`(348)、`description`(337) 均已存在于 `workflow_def` 表。仅后端投影 SQL 多取一列 + DTO 增字段，属纯读路径扩展。schema_version 维持现状，无 data.sql / 迁移。

**Alternatives**: 仿 038 加快照列——否决，字段已存在无需物化。

## D2: 后端投影 / Query / SQL 扩展方案

**Decision**（接缝：`OpsService#queryWorkflows` 137-204、`OpsContracts` 102-113、`OpsController` 118-160）:
- `WorkflowListRow` record 增 `String nextTriggerTime`（ISO 字符串，同 `lastFireTime` 哲学，null 透传）。
- `WorkflowQuery` record 增 `String priorityTier`（"high"|"normal"|null）、`String sortField`（白名单 "priority"|null）、`String sortDir`（"asc"|"desc"，默认 asc）。
- `OpsService#queryWorkflows`：
  - SELECT 增 `wd.next_trigger_time`；RowMapper 增映射（LocalDateTime→toString）。
  - WHERE 增 priorityTier 区间：`high`→`AND wd.priority BETWEEN 0 AND 2`；`normal`→`AND wd.priority BETWEEN 3 AND 9`。
  - ORDER BY 由写死 `ORDER BY wd.id LIMIT ? OFFSET ?` 改为：sortField=priority 时 `ORDER BY wd.priority <dir> NULLS LAST, wd.id LIMIT ? OFFSET ?`，否则维持现状。
- `OpsController` 两端点增 `@RequestParam(required=false) String priorityTier, String sort`，解析 `sort=field:dir` 传入 WorkflowQuery。

**Rationale**: 复用现有 JdbcTemplate 拼接风格；排序字段白名单（仅 "priority"）防 SQL 注入；`NULLS LAST` 兜底历史 null 行；`BETWEEN`/`NULLS LAST`/`LIMIT OFFSET` H2 与 PG 均支持（见 MEMORY `h2-pg-sql-dialect-traps`，已避坑 `||` 改 CONCAT）。

**Alternatives**: priority 单值筛选——否决，UI 是分档语义；priorityMin/Max 区间——否决，比 tier 字符串冗余。

## D3: 优先级筛选语义 = tier 分档

**Decision**: 前端 segmented filter value = `"high"` | `"normal"` | `""`（空=全部），直传后端 `priorityTier`。

**Rationale**: 呼应 FR-009 高优阈值（≤2）；`segmented` 是 `FilterDef` 既有 kind（`hasDraftChange` 已用，见 periodic panel 98-106），零新交互形态；`toQueryParams` 自动转 `priorityTier=high`。

**Alternatives**: 0–9 数值下拉——否决，太碎，运维心智是"高优 vs 普通"。

## D4: 排序扩展 DataTable 公共组件（影响面评估）

**Decision**: 排序能力上提到 `unified-data-table` 公共层，非 panel 私有实现。
- `ColumnDef` 增 `sortable?: boolean`（缺省 false）。
- `FetchQuery` 增 `sort?: { field: string; dir: "asc"|"desc" }`。
- `toQueryParams` 增 `sort=field:dir` 序列化；server fetcher 透传。
- `DataTable` 表头：sortable 列渲染可点击表头 + hugeicons 方向图标（升/降/未排序三态），点击 asc→desc→清除。
- 同步 `data-table.test.ts`（toQueryParams sort 序列化纯逻辑）。

**Rationale**: server 分页下列表排序必须 server 端，逻辑属 DataTable 公共能力；`unified-data-table` 本为复用而设。`sortable` 缺省 false → 现有列/其它列表（实例列表等）不声明即不受影响，**向后兼容**。

**风险与缓解**: 表头改动触及所有 DataTable 列表视觉。缓解：sortable 缺省 false，仅优先级列开启；排序 UI 仅在该列表头出现。

**Alternatives**: 仅本 panel 私有排序——否决，违背 unified-data-table 复用且 client/server 双模式难统一。

## D5: 相对时间 util 设计（新建）

**Decision**: 新建 `frontend/lib/relative-time.ts`，**纯函数返回结构化结果，i18n 在调用方**。

```ts
// 返回 null = 输入空；否则 { key, values } 供 next-intl t() 用
export function relativeNextTrigger(
  iso: string | null,
  now: Date,
): { key: string; values: Record<string, number> } | null
```

三态映射（FR-008）：
- 临近（0 ≤ diff < 1min，未来）：`{ key: "relSoon" }` → "即将" / "soon"
- 未来 ≥1min：按 `relInMinutes` / `relInHours` / `relInDays` 选最大合适单位（≥7d 仍用 days，避免大数字）
- 已过期（diff<0）：`relExpiredMinutes` / `relExpiredHours`

**Rationale**: 纯函数 → vitest 直接断言结构（不依赖 locale/DOM）；i18n key 集中在 messages，满足"两 bundle 一致 + CI 可检"；`now` 由调用方注入（可测、避免 `new Date()` 副作用）。`humanizeCron`（`cron-format.ts`）是同类纯函数先例，遵循相同模式。

**Alternatives**: util 内置中英字符串——否决，违反 i18n 文件归属；util 接收 t 函数——否决，破坏纯函数可测性。

## D6: 列宽重分配建议（tasks 阶段定稿）

**Decision**: 加列后 `widthPct` 重分配到 100，建议值：
- 周期（9 列，+下次触发+优先级，描述副标题不占列）：名称 20 / 下次触发 14 / Cron 12 / 最近触发 10 / 状态 8 / 上次运行 14 / 版本 8 / 优先级 8 / 操作 6
- 手动（7 列，+优先级）：名称 24 / 最近触发 12 / 状态 8 / 上次运行 18 / 版本 8 / 优先级 10 / 操作 20（含 runOnce + DAG 两按钮）

**Rationale**: 名称/操作保持可用宽度；新增列从次要列匀出；具体值实现时按视觉微调，须保证总和=100。

**Alternatives**: 横向滚动——否决，违反 SC-003。

## 未决（→ tasks / 实现，均有强默认）
- 描述副标题截断：用既有 `truncate` + tooltip（name 列已有 `title` 模式），不硬定字符数。
- 窄屏隐藏次要列：本 feature 不做（无响应式列隐藏框架），后续视反馈再加。
