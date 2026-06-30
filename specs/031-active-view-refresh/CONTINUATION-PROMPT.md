# 续跑提示词 — 031 激活页自动无感刷新（交给执行 Agent）

你是执行 Agent，接手 Weft 项目特性 **031-active-view-refresh** 的前端实现。设计/规格已完成并已落了核心逻辑，你的任务是把剩余实现做完、跑绿、可演示。

## 0. 硬约束（违反即返工）
1. **只在 worktree 里干活**：`cd /home/wallfacers/project/dw-031-active-view-refresh`，分支 `031-active-view-refresh`。**绝不** `cd` 到 `/home/wallfacers/project/data-weave`（主副本被别的 Agent 占用）、**不切分支**、**不动别人的未提交改动**。
2. **改动面仅 `frontend/`**，无后端、无新依赖（不许装 jsdom/@testing-library 等）。
3. **每次改完前端跑 `cd frontend && pnpm typecheck`，零错误才继续**；改到核心逻辑跑 `npx vitest run lib/workspace/refresh-scheduler.test.ts`。最后跑 `pnpm test`（=`vitest run`）全绿。
4. 遵守 `frontend/CLAUDE.md` 前端栈门：shadcn **base-style**（自定义 trigger 用 `render` 不用 `asChild`；Button 当 `<a>` 用 `render={<Link/>}` 要 `nativeButton={false}`）；图标一律 **hugeicons** `<HugeiconsIcon icon={XxxIcon}/>`（不用 lucide）；语义 token（`text-muted-foreground`/`bg-accent`），`size-*`、`gap-*`。
5. **改任何视觉/设计系统前先读 `frontend/DESIGN.md`** 并在改动里遵守（表格三段式 toolbar/flex-1/pagination 不破坏；刷新中用图标旋转**不要用 `…`** 表进行中）。
6. i18n：新增静态文案走 `frontend/messages/{zh-CN,en-US}.json`，**两 bundle 键集必须一致**（CI 校验）。
7. WSL2：typecheck/vitest 很快无需脱离；若起 `pnpm dev` 等长进程，用 `setsid bash -c '... >log 2>&1' </dev/null >/dev/null 2>&1 & disown` 脱离，单次秒回轮询，禁前台 sleep 循环。
8. 完成的任务在 `specs/031-active-view-refresh/tasks.md` 里打勾 `[X]`。**不要 commit**（除非用户要求）。

## 1. 先读（必须）
`specs/031-active-view-refresh/` 下：`tasks.md`（任务真相源，含「实现进度」段）、`spec.md`（FR/SC/验收）、`plan.md`、`data-model.md`（hook 契约）、`research.md`（决策 D1–D10）、`contracts/endpoints.md`、`quickstart.md`（验证脚本）。

## 2. 已完成（勿重写，直接复用）
- `frontend/lib/workspace/refresh-scheduler.ts`：纯逻辑核心。
  - `createRefreshScheduler(onTick: ()=>void|Promise<void>, intervalMs, initial?: Partial<RefreshSignals>): RefreshScheduler`，方法 `update(Partial<{active,enabled,visible}>)`、`tickNow(): Promise<void>`、`isRunning()`、`dispose()`。**gating + 进入运行边沿立即刷新 + in-flight 去重/不堆叠/合并 + 创建即运行** 已实现并测过。
  - `liveDataReducer/initialLiveDataState` + `LiveDataState<T>`/`LiveDataAction<T>`：start（有数据→refreshing、无→loading，**不清空 data**）、success（填 data+lastUpdatedAt、清 error/stale）、error（保留 data、置 stale）。
- `frontend/lib/workspace/refresh-scheduler.test.ts`：14 例全绿。新增逻辑请在此文件补测。

## 3. 要实现的（按相位，引用 tasks.md 的 T 号）

### A. Setup + Foundational（T001–T006）
- **T001/T003**：在 `frontend/lib/workspace/use-api.ts`：
  - 导出 `export const LIVE_REFRESH_INTERVAL_MS = 30_000`。
  - 抽出 `export async function fetchApi<T>(path: string): Promise<T>`：复刻 `useApi` 内的取数（`localStorage` token→`Authorization: Bearer`、`Accept-Language: acceptLanguageHeader()`、`cache:"no-store"`；`res.status===401`→`handleUnauthorized()`+throw；解包 `ApiResponse<T>`，`json.code===0` 返回 `json.data`，否则 `throw new Error`）。`useApi` 改为复用 `fetchApi`，**对外签名与「refetch 即清空」语义不变**。
- **T002**：两 bundle 加 `viewRefresh` 命名空间：`lastUpdated`（如 zh「最后更新 {time}」/en「Updated {time}」，用 ICU）、`auto`（「自动刷新」/「Auto-refresh」）、`paused`（「已暂停」/「Paused」）、`updateFailed`（「更新失败」/「Update failed」）、`stale`（「数据可能过时」/「Data may be stale」）。刷新动作复用既有 `common.refresh`。
- **T004**：`frontend/lib/workspace/registry.tsx` 的 `ViewProps` 加 `active?: boolean`。
- **T005**：`frontend/components/workspace/workspace.tsx` 渲染处改 `<View params={tab.params} active={tab.id === activeTabId} />`。
- **T006**：新建 `frontend/components/workspace/views/view-refresh-control.tsx`，presentational，props 见 data-model §2：`{ lastUpdatedAt, refreshing, stale, autoEnabled?, onToggleAuto?, onRefresh }`。先实现「最后更新时间 + 刷新按钮（`RefreshIcon` size-4 ghost，`refreshing` 时图标 `animate-spin` + disabled）」；`onToggleAuto` 未传则不渲染开关（US1 期）。stale 时显示非打断小标（`viewRefresh.stale`）。文件头注释声明采纳的 DESIGN.md 约束。

### B. 两个 React 薄包装 hook（在 `use-api.ts`，复用 §2 核心）—— T007/T007b
精确签名：
```ts
export function useRefreshSchedule(
  onTick: () => void | Promise<void>,
  opts: { active?: boolean; enabled?: boolean; intervalMs?: number },
): { tickNow: () => Promise<void> }

export function useLiveData<T>(
  source: string | (() => Promise<T>),
  opts?: { active?: boolean; enabled?: boolean; intervalMs?: number; deps?: unknown[] },
): LiveDataState<T> & { refresh: () => Promise<void> }
```
实现要点（重要，避免坑）：
- 用 `useRef` 持 scheduler 与「最新 onTick」：`createRefreshScheduler(() => onTickRef.current(), intervalMs, {active,enabled,visible})`，scheduler **只创建一次**（mount effect），`onTickRef.current` 每 render 更新，从而闭包始终最新。
- 可见性：`visible` 初值 `typeof document==='undefined' ? true : document.visibilityState!=='hidden'`；mount 时 `addEventListener('visibilitychange', ...)`→`scheduler.update({visible})`，unmount 移除 + `scheduler.dispose()`。
- `active`/`enabled` 变化用一个 effect `scheduler.update({active,enabled})`（依赖 `[active,enabled]`）。
- `useLiveData`：`useReducer(liveDataReducer, undefined, initialLiveDataState<T>)`；`onTick = async () => { dispatch{start}; const gen=++genRef; try { const d = await fetcher(); if(gen===genRef) dispatch{success,d,at:Date.now()} } catch { if(gen===genRef) dispatch{error} } }`，其中 `fetcher = typeof source==='string' ? ()=>fetchApi<T>(source) : source`。`deps`/`source` 变化：`genRef`++（丢弃迟到）+ `scheduler.tickNow()` 重取。`refresh = () => scheduler.tickNow()`。返回 `{...state, refresh}`。
- 卸载用 `genRef` 守卫避免 setState-after-unmount。

> 注：gating 与边沿逻辑已在核心实现，wrapper 只负责把 `active` prop + document 可见性喂进去——**T019/T020 实质即 wrapper 接 visibility，做完 B 即同时完成 US2 逻辑**；US2 的浏览器实测（T022）仍要单独做。

### C. DataTable 回调（T014）
`frontend/components/ui/data-table.tsx` 加可选 props：`reloadSignal?: number`、`onLoadingChange?: (loading:boolean)=>void`、`onLoaded?: ()=>void`。
- 把 `reloadSignal` 加进 server 取数 effect 依赖 `[mode, values, page, size, reloadNonce, reloadSignal]`。
- effect 内：开始 `onLoadingChange?.(true)`；`.then(res=>{ if(!cancelled){ setServerData(res); onLoaded?.() }})`；**`.catch`：若已有 `serverData` 则保留（不要 setServerData(null)）以满足无感/FR-010，仅初始无数据时置 null**；`.finally(onLoadingChange?.(false))`。注意 `onLoadingChange/onLoaded` 用 ref 包装避免进 effect 依赖导致重复取数。

### D. 视图接线（T009–T013 取数类；T015–T017 表格类）
所有视图组件签名改为接收 `active`（从 `ViewProps`），渲染规则统一改为 **`if (data==null && loading) return <ViewStatus loading/>`**，有过数据后一律原地替换（删除「refetch 即 ViewStatus」的闪屏写法）；header/toolbar 右侧挂 `<ViewRefreshControl>`。
- **T009 metrics-view**：`useApi(...)`→`useLiveData<MetricsSnapshot>("/api/ops/metrics",{active})`；header（`flex items-center justify-between`）右侧放控件。
- **T010 reports-view**：`useLiveData<MetricCard[]>("/api/metrics",{active})`。
- **T011 ops/top-strip**：两个 `useApi`→`useLiveData`（`/api/ops/summary`、`/api/ops/eta-summary`），`active` 由 `ops-view` 透传（**ops-view 是复合视图，要把自己的 `active` 往 TopStrip 与实例面板透传**）。
- **T012 quality-view**：自定义 `authFetch` 改 `useLiveData`（单 path 或 fetcher）。
- **T013 alerts-view**：把 `Promise.all` 4 端点封成 `fetcher: ()=>Promise<Bundle>` 传 `useLiveData(fetcher,{active})`；任一端点失败 → fetcher reject → 保留旧数据置 stale。
- **T015 freshness-view / T016 periodic-instances-panel / T017 workflow-instances-panel**：数据仍由 `DataTable` 自取；用 `useRefreshSchedule(onTick,{active,enabled})`，`onTick = () => setReloadSignal(n=>n+1)`，把 `reloadSignal` 传 `DataTable`；用 `DataTable` 的 `onLoadingChange/onLoaded` 把 `refreshing/lastUpdatedAt` 喂给 toolbar 右侧 `<ViewRefreshControl>`（用 `DataTable` 的 `toolbarActions` 槽放控件）；手动刷新按钮 → `schedule.tickNow()`。验证翻页/筛选态刷新不回第一页（DataTable 内 page/filter 不因 reloadSignal 重置）。

### E. US3（T023–T027）
- **T023**：`view-refresh-control.tsx` 补「自动刷新开关」（默认开、`onToggleAuto`，用 base-style Switch 或带 label 的 toggle）与 paused/stale 文案；最终对照 DESIGN.md 定稿。
- **T024**：合并已在核心（`tickNow` 命中在途复用），确认 `refresh()` 不受 enabled 限制（核心已如此）。
- **T025**：各统计视图加 `const [autoEnabled,setAutoEnabled]=useState(true)`，传 `useLiveData`/`useRefreshSchedule` 的 `enabled` + 控件的 `autoEnabled/onToggleAuto`。卸载/重开/刷新整页自然回 true。
- **T026**：在 `refresh-scheduler.test.ts` 补：`refresh()`/`tickNow` 合并、`enabled=false` 下 `tickNow` 仍发起（已有近似用例，确认覆盖）。
- **T027**：浏览器走查（quickstart 步骤 5/6/8）。

### F. Polish（T028–T031 + T030b）
- T028 失败态走查（quickstart 7）；T029 i18n 键一致；T030 DESIGN 门复核；**T030b** FR-013 回归：确认 `instance-log`(SSE)/canvas/settings/详情/占位视图未引入新 hook、`useApi` 语义未变；T031 全量 `pnpm typecheck` + `pnpm test` 全绿，按 quickstart「完成判据」签收。

## 4. 浏览器验证（过鉴权）
admin/admin 登录拿 JWT 注入 `localStorage` `dw.auth.token`；深链 `/?open=metrics` 等。双 tab 用 DevTools Network 验后台请求数=0、切回即刷。

## 5. 交回前自检清单
- [ ] `pnpm typecheck` 0 错；`pnpm test` 全绿（含新增用例）。
- [ ] 6 类视图（metrics/reports/freshness/ops/quality/alerts）都有统一控件、自动无感刷新、最后更新时间、会话内开关。
- [ ] 后台/隐藏页请求数=0，切回/窗口可见即刷一次。
- [ ] 表格视图刷新不回第一页、不丢筛选。
- [ ] 失败保留旧数据 + 非打断 stale 提示、无全屏错误。
- [ ] tasks.md 已逐项打勾；未 commit。
- [ ] 全程未触碰主副本 `/home/wallfacers/project/data-weave`。

完成后回报：改了哪些文件、typecheck/test 结果原文、哪些 quickstart 步骤实测过、遗留问题。**不要谎报；失败就贴输出。**
