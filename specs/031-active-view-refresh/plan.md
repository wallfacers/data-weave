# Implementation Plan: 激活页统计数据自动无感刷新 + 统一手动刷新控件

**Branch**: `031-active-view-refresh` | **Date**: 2026-07-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/031-active-view-refresh/spec.md`

## Summary

为工作区的统计/指标类页面（metrics·reports·freshness·ops·quality·alerts）引入「激活态驱动的自动无感刷新 + 每页会话内开关 + 统一手动刷新控件」。核心是**前端纯改造**：① 把当前激活 tab 信号传给视图；② 一个生命周期感知的取数 hook，在「激活 && 开关开 && 窗口可见」时按统一 ~30s 周期轮询，保留上次成功数据、不闪、不堆叠、不并发；③ 一个统一的 `<ViewRefreshControl>`（最后更新时间 + 刷新按钮 + 自动刷新开关）放在各视图头部。复用各页现有读端点，**无后端改动**。

## Technical Context

**Language/Version**: TypeScript 5 / React 19 / Next.js 16（App Router, Turbopack）—前端唯一改动面

**Primary Dependencies**: next-intl（静态文案）、zustand（workspace store，激活态来源）、shadcn base-style + hugeicons（`RefreshIcon`）、现有 `useApi` / `DataTable` / `DwScroll`

**Storage**: N/A（无持久化；自动刷新开关为会话内 React state，不落 localStorage/后端）

**Testing**: vitest（hook 调度逻辑单测：激活/失活启停、可见性、去重、不堆叠、错误保留旧数据）+ 浏览器实测（无感更新、滚动/筛选/分页保持、切回即刷新）

**Target Platform**: 现代浏览器（前端 SPA）

**Project Type**: Web application（仅 `frontend/`，无 `backend/` 改动）

**Performance Goals**: 激活页 ≤30s 自动可见后端变更；后台页轮询请求数=0；同源并发重复请求=0；自动刷新单次不阻塞交互（无全屏 loading）

**Constraints**: 无感更新——刷新时不重置为空、不出现全屏遮罩、不重排导致滚动/展开/筛选/分页/选中丢失；请求不堆叠（在途未结束不发下一次）；失败保留上次成功数据并以非打断方式提示

**Scale/Scope**: 6 类视图纳入（其中 metrics/reports/ops-top-strip 用 `useApi`；freshness/ops 实例面板用 `DataTable` server 模式；alerts/quality 用自定义 fetch）；约 1 个新 hook + 1 个新组件 + 各视图接线 + workspace 传 active 信号

## Constitution Check

**结论：PASS（无违背项）。** 本特性为纯前端 UX 增强，不触及宪法治理内核：

- **I. Files-First**：不涉及任务/工作流定义的文件表示——无关。
- **II. Server is Source of Truth**：只读复用现有端点，不改写治理/快照语义——无关。
- **III. 分阶段运行时 / IV. AI 在本地 agent / V. 拆除不得损伤可观测与调度**：无后端、无调度、无 MCP/CLI、无 AI 面改动——无关，且**不削弱**任何可观测性（反而让指标页更实时）。
- **侧效门禁（PolicyEngine/agent_action）**：本特性只发起**只读** GET，不产生任何写动作，无需经闸门——符合约定。

无 gate 违规，无需 Complexity Tracking 例外。

## Project Structure

### Documentation (this feature)

```
specs/031-active-view-refresh/
├── spec.md              # 已完成（specify + clarify）
├── plan.md              # 本文件
├── research.md          # Phase 0 决策记录
├── data-model.md        # 前端刷新状态模型（无库表）
├── contracts/
│   └── endpoints.md     # 复用的只读端点清单（无新增/无改动）
├── quickstart.md        # 验证步骤
└── checklists/
    └── requirements.md  # spec 质量清单（16/16）
```

### Source Code (repository root) — 仅 `frontend/`

```
frontend/
├── lib/workspace/
│   ├── use-api.ts                    # 抽出共享 fetchApi() + 新增 useLiveData(fetcher|path) + useRefreshSchedule()（仅调度，表格视图用）；保留旧数据/轮询/去重/可见性/lastUpdated/手动 refresh/开关
│   ├── store.ts                      # （读）activeTabId — 不改语义
│   └── registry.tsx                  # ViewProps 增加 active?: boolean
├── components/workspace/
│   ├── workspace.tsx                 # 渲染时把 active={tab.id===activeTabId} 传给 <View>
│   └── views/
│       ├── view-refresh-control.tsx  # 新增：统一控件（最后更新时间 + 刷新按钮 + 自动刷新开关）
│       ├── metrics-view.tsx          # 接线 useLiveData + 控件；保留旧数据不闪
│       ├── reports-view.tsx          # 同上
│       ├── ops-view.tsx / ops/*      # top-strip 用 useLiveData；DataTable 面板用 reload 信号
│       ├── freshness-view.tsx        # DataTable 自动 reload（保留分页/筛选）
│       ├── quality-view.tsx          # 自定义 fetch 接 useLiveData 模式
│       └── alerts-view.tsx           # 同上（Promise.all 多端点）
├── components/ui/
│   └── data-table.tsx                # 暴露受控 reload 入口 reloadSignal + 完成回调 onLoadingChange/onLoaded（回灌 refreshing/lastUpdatedAt）
└── messages/{zh-CN,en-US}.json       # 新增 viewRefresh.* 文案（lastUpdated/auto/paused/updateFailed/stale）；refresh 复用 common.refresh
```

新增测试：
```
frontend/lib/workspace/__tests__/use-live-data.test.ts   # 调度/去重/可见性/错误保留 单测
```

## Phase 0 — Outline & Research

见 [research.md](./research.md)。需解决的关键决策：
1. **激活信号如何传递**（store 订阅 vs prop 下传）→ 选 prop 下传 `active`。
2. **轮询 hook 的形态**（扩展 useApi vs 新 useLiveData）→ 新 hook，复用抽出的 `fetchApi`。
3. **无感更新如何实现**（保留旧 data、不重置 null；refreshing 与 loading 分离）。
4. **去重 / 不堆叠 / 手动+自动合并**（in-flight ref + 取消标记 + 周期跳过）。
5. **窗口可见性叠加**（`document.visibilityState` + `visibilitychange`）。
6. **DataTable 视图如何无感刷新且保留分页/筛选**（复用其内建 `reloadNonce` 的 in-place 重取，向上暴露 reload 入口；避免 datasources 式 remount）。
7. **统一控件的位置与样式**（DESIGN.md：卡片头部 / DataTableToolbar 右侧；`RefreshIcon` size-4 ghost；刷新中旋转）。
8. **会话内开关状态载体**（视图内 `useState`，随卸载/重开/刷新整页重置为开）。

## Phase 1 — Design & Contracts

- **data-model.md**：定义 `LiveDataState<T>`、`RefreshControlState`、`useLiveData` 选项与返回；激活/可见/开关三信号的真值表；DataTable reload 接线契约。
- **contracts/endpoints.md**：列出复用的只读端点（`/api/ops/metrics`、`/api/metrics`、`/api/freshness`、`/api/ops/summary`、`/api/ops/eta-summary`、ops 实例分页、quality、alerts 四端点），明确**零新增、零签名改动**。
- **quickstart.md**：本地验证脚本（双 tab 切换观测请求数、无感更新走查、失败保留旧数据、开关会话内行为、跨页控件一致性）。
- **agent context update**：运行 `.specify/scripts/bash/update-agent-context.sh claude`。

### 关键设计要点（落到 data-model）

- **两种消费形态**（analyze I1/I2 收口）：① 自取数视图（metrics/reports/ops-top-strip/quality/alerts）用 `useLiveData(fetcher, opts)`——`fetcher` 闭包覆盖 alerts 多端点 `Promise.all` 聚合，单端点传 `path` 便捷重载；② 表格视图（freshness/ops 实例面板）数据由 `DataTable` 自取，改用**仅调度** `useRefreshSchedule(onTick, opts)` 驱动 `reloadSignal`，避免双重请求；`DataTable` 增 `onLoadingChange/onLoaded` 回调把 `refreshing/lastUpdatedAt` 回灌控件（U1）。
- `useLiveData<T>(fetcher | path, { active, enabled, intervalMs=30000, deps })` 返回 `{ data, loading, refreshing, error, stale, lastUpdatedAt, refresh() }`：
  - 首次：`loading=true`，无旧数据→可显示骨架/ViewStatus。
  - 之后每次 refetch：**不清空 data**，仅 `refreshing=true`；成功→更新 data + lastUpdatedAt + 清 stale；失败→保留 data + `error/stale=true`，下一周期重试。
  - 调度：仅当 `active && enabled && documentVisible` 时设定 interval；任一转 false 即清除 timer（后台请求数=0）。
  - 转入 `active`（或窗口重新可见）边沿→立即 `refresh()` 一次。
  - 去重：`inFlight` ref；周期 tick 命中在途则跳过（不堆叠）；手动 `refresh()` 命中在途则复用同一在途 promise（合并，不并发）。
- `<ViewRefreshControl>`：props `{ lastUpdatedAt, refreshing, stale, autoEnabled, onToggleAuto, onRefresh }`；统一渲染「最后更新时间（相对/绝对）+ 刷新按钮（RefreshIcon，refreshing 时旋转、disabled）+ 自动刷新开关（默认开，会话内）」。
- 自动刷新开关 = 视图内 `const [autoEnabled, setAutoEnabled] = useState(true)`，传入 `useLiveData` 的 `enabled` 并驱动控件——卸载/重开 tab/刷新整页自然回 true（满足 FR-014 会话内不持久化）。

## Complexity Tracking

无需例外。本特性不新增项目/模块/层级，复用既有 hook/组件与端点，复杂度集中在 1 个 hook 的并发/生命周期细节（由单测覆盖）。
