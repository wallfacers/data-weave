# Research: 实例列表排序 + 操作按钮状态化

## Decision 1: 排序架构 — Server-side sort

**Decision**: 排序在后端 SQL 层执行（server-side sort），不在前端内存中排序。

**Rationale**: 实例列表已有分页查询（page/size），数据量可能达数万条。前端 client-side sort 需要加载全量数据才能正确排序，与分页语义冲突。后端排序直接利用数据库索引，性能最优。现有 `queryWorkflows` 已有 server-side sort 先例（`sort=priority:desc`）。

**Alternatives considered**:
- Client-side sort: 需要拉全量数据，破坏分页，仅适合小数据集（<200 行）
- Hybrid (server default + client resort): 复杂性高，收益低

## Decision 2: 排序参数格式 — `field:dir`

**Decision**: 沿用现有 `sort=priority:desc` 格式（`field:dir`），不再设计额外 DTO。

**Rationale**: `periodic-workflows` 和 `manual-workflows` 端点已使用此格式，`DataTable.toQueryParams` 也已按此格式序列化。一致性优先。

## Decision 3: 默认排序行为 — 无 sort 参数时保持现有优先级排序

**Decision**: 旧行为不变。仅当显式传递 `sort` 参数时才切换到指定字段排序。

**Rationale**: 现有硬编码排序（FAILED/STOPPED 优先 → RUNNING 次之 → 其余）服务于运维"先看故障"的既有工作流。前端在首次加载时通过 fetcher 构造 `{ field: "scheduledFireTime", dir: "desc" }` 实现默认时间倒序，无需改后端隐式行为。用户手动清除排序后，回退到优先级排序。

## Decision 4: 排序字段白名单 — Java switch

**Decision**: 后端使用 `switch` 语句做字段名 → SQL 列名白名单映射，非白名单字段回退到默认排序。

**Rationale**: 沿用 `queryWorkflows` 的 `orderByClause()` 模式（`if "priority" → wd.priority`）。动态拼接列名有 SQL 注入风险，白名单彻底消除。可排序列有限（5 个），`switch` 足够。

## Decision 5: Null 排序语义 — NULLS LAST

**Decision**: 所有字段降序时 `NULLS LAST`（null 排最末），升序时也 `NULLS LAST`（null 排最末）。

**Rationale**: 精炼自 FR-007（scheduledFireTime null 降序排末）。scheduledFireTime 在手动/补数据触发时为 null，运维人员通常关注定时触发的实例，null 排末符合作业直觉。`bizDate`/`startedAt`/`finishedAt` 理论上不应为 null，但加 `NULLS LAST` 无害。

## Decision 6: 按钮禁用 — 纯前端状态推导

**Decision**: 按钮禁用逻辑为纯前端 `state → action[]` 映射，不调后端校验。

**Rationale**: 按钮禁用是 UI 防护层（防误操作），后端校验保持不变。前端状态来自列表数据（`row.state`），映射表简单、零网络开销、即时响应。即使前端映射出错，后端仍会拒绝非法操作并返回错误提示（双重保障）。

## Decision 7: 排序持久化 — URL searchParams + DataTable 初始值

**Decision**: 排序状态写入 URL query param（`?sort=scheduledFireTime:desc`），DataTable 组件从 URL 读取初始排序。

**Rationale**: 用户选择 A（完全持久化）。URL 参数天然支持浏览器前进/后退、书签、分享。跨 Tab 切换时，组件根据 URL 恢复排序状态。DataTable 已通过 `sort` prop 支持受控模式。

## Decision 8: 批量操作按钮逻辑 — 所有选中项的交集

**Decision**: 批量按钮的 `disabled` 状态取所有选中行的 `isActionEnabled` 逻辑 AND。任一行的目标操作不可用 → 按钮整体禁用。

**Rationale**: FR-013 明确"任意选中项处于不可操作状态时禁用"。实现为 reducer：`selectedRows.every(row => isActionEnabled(row.state, action))`。
