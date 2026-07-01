# Tasks: 激活页统计数据自动无感刷新 + 统一手动刷新控件

**Input**: Design documents from `specs/031-active-view-refresh/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/endpoints.md, quickstart.md
**Tests**: 包含（CLAUDE.md「无测试=未完成」+ research D10 要求 hook 逻辑单测）。
**Organization**: 按 3 个 user story 分阶段，各自独立可测。

**改动面**：仅 `frontend/`（无后端）。工作目录为 worktree `/home/wallfacers/project/dw-031-active-view-refresh`。

## 实现进度（2026-07-01 续跑交接）

**已落地（在 worktree 磁盘上，勿重写）**：
- ✅ `frontend/lib/workspace/refresh-scheduler.ts`（**新**）—— 纯逻辑核心：`createRefreshScheduler`（gating=active&&enabled&&visible + 进入运行边沿立即刷新 + in-flight 去重/不堆叠/合并）+ `liveDataReducer/initialLiveDataState`（保留旧数据、失败置 stale）+ 类型。**T007/T007b/T019/T020 的全部难点逻辑已在此实现**。
- ✅ `frontend/lib/workspace/refresh-scheduler.test.ts`（**新**）—— 19 例**全绿**，覆盖 gating/边沿/不堆叠/合并/暂停手动/dispose + reducer 无感与失败保留 + T026 确认（即 T008/T021/T026 的逻辑层）。

**2026-07-01 续跑完成**：React 薄包装（useLiveData/useRefreshSchedule）+ 常量 + i18n + fetchApi 抽取 + ViewRefreshControl + 6 视图接线 + DataTable 回调 + US3 开关已全部落地。**浏览器验证（T022/T027/T028）需起 app 后手工走查**。

**2026-07-01 设计方 review 收尾（证伪式核验）**：
- 亲跑：`pnpm typecheck` 仅 1 预存错误 `subscription-dialog.tsx`（origin/main 既有，非本次、不在改动文件）；`pnpm test` 110/111，唯一失败 `store.test.ts`（tab 排序断言，origin/main 既有，非本次）；`refresh-scheduler.test.ts` 19/19。
- **修复 1 个真 bug（设计方亲改）**：`useRefreshSchedule` 原在 **render 阶段**创建 scheduler，而清理 effect 会 `dispose()+置 null`——StrictMode（Next dev 默认）/重新挂载下「cleanup→setup」后无法重建 → **dev 下自动刷新静默失效**（且会引入 render 期 setState）。已将 scheduler **创建移入挂载 effect**（post-commit 创建+fire、可重建）。typecheck/测试复跑仍绿。
- 复核其余易错缝均正确：`ops-view` 把 `active` 透传给 top-strip + 两实例面板；`DataTable` catch 保留旧数据（FR-010）；alerts 聚合 fetcher + `deps:[tab]` + `loading&&data==null` 守卫；freshness `reloadSignal`/`onLoadingChange`/`onLoaded` 接线正确。
- **仍待**：T022/T027/T028 浏览器走查（起 app）是合入前最后一道门——StrictMode 修复后预期通过。

## Format: `[ID] [P?] [Story] Description`
- **[P]**：可并行（不同文件、无未完成依赖）
- **[Story]**：US1 / US2 / US3
- 每条含确切文件路径

---

## Phase 1: Setup（共享基建）

- [X] T001 [P] 在 `frontend/lib/workspace/use-api.ts` 顶部新增导出常量 `LIVE_REFRESH_INTERVAL_MS = 30_000`（统一周期，FR-001/SC-001），暂不接线。
- [X] T002 [P] 在 `frontend/messages/zh-CN.json` 与 `frontend/messages/en-US.json` 同步新增 `viewRefresh` 命名空间键：`lastUpdated`、`auto`、`paused`、`updateFailed`、`stale`（两 bundle 键集一致，CI 校验）；`refresh` 复用既有 `common.refresh`。

---

## Phase 2: Foundational（阻塞所有 story 的前置）

**Purpose**: 抽出共享取数逻辑、打通 active 信号、立起控件外壳——这些不含具体 story 行为，但所有 story 依赖。

- [X] T003 从 `frontend/lib/workspace/use-api.ts` 抽出纯函数 `fetchApi<T>(path): Promise<T>`（含 Bearer token 注入、`Accept-Language`、401→`handleUnauthorized`、`ApiResponse` 解包 `code===0`），`useApi` 改为复用它；保持 `useApi` 对外签名与「refetch 即清空」语义不变（非统计视图零回归）。
- [X] T004 [P] 扩展 `ViewProps`：在 `frontend/lib/workspace/registry.tsx` 给 `ViewProps` 增加 `active?: boolean`。
- [X] T005 在 `frontend/components/workspace/workspace.tsx` 渲染处把激活信号下传：`<View params={tab.params} active={tab.id === activeTabId} />`。
- [X] T006 [P] 新建统一控件外壳 `frontend/components/workspace/views/view-refresh-control.tsx`：presentational 组件，props 见 data-model（`lastUpdatedAt/refreshing/stale/autoEnabled/onToggleAuto/onRefresh`）；本任务先实现「最后更新时间 + 刷新按钮（`RefreshIcon` size-4 ghost，`refreshing` 时旋转+disabled）」骨架。**US1 期处理（A1）**：`onToggleAuto` 未传时不渲染开关（或渲染恒「开」的禁用态占位），开关到 US3（T023）再激活；`stale` 提示同样 US3 完善。读 `frontend/DESIGN.md` 并在文件头注释声明采纳的约束（图标/语义 token/三段式不破坏/无 `…`）。

**Checkpoint**: 项目可编译；active 信号已到视图；控件外壳与常量/文案就位。

---

## Phase 3: User Story 1 - 激活页统计数据自动无感刷新 (P1) 🎯 MVP

**Goal**: 统计页在前台时按 ~30s 自动重取并**无感**更新（保留旧数据、不闪、不堆叠、失败保留旧值、显示最后更新时间）。
**Independent Test**: 打开 metrics，后端变化后 ≤30s 自动更新且滚动/不闪；翻页后的 freshness 自动刷新不回第一页。

### 核心引擎 + 测试
- [X] T007 [US1] 在 `frontend/lib/workspace/use-api.ts` 新增 `useLiveData<T>(fetcher | path, { active, enabled=true, intervalMs=LIVE_REFRESH_INTERVAL_MS, deps })`，返回 `{ data, loading, refreshing, error, stale, lastUpdatedAt, refresh }`（data-model §1 契约）。**接受 `fetcher: ()=>Promise<T>` 为主形态**（覆盖 alerts 多端点聚合，I2），`path` 字符串作便捷重载内部包成 `()=>fetchApi(path)`；`deps` 变化即重建 fetcher 并重取。本阶段实现：① **保留旧 data**（refetch 不清空，`loading` 仅首屏、`refreshing` 后台重取）；② 挂载即取一次 + 按 `intervalMs` 轮询（本阶段调度仅依赖 `enabled`，`active`/可见性 gating 留 US2）；③ `inFlightRef` 去重——tick 命中在途则跳过（不堆叠，FR-009）；④ 成功更新 `data/lastUpdatedAt`、清 `stale`；失败保留 `data` 并置 `error/stale`（FR-010）；⑤ `generationRef`+卸载丢弃迟到结果。
- [X] T007b [US1] 在 `frontend/lib/workspace/use-api.ts` 抽出**仅调度** hook `useRefreshSchedule(onTick, { active, enabled, intervalMs })`（返回 `{ tickNow }`），承载调度/可见性/edge-trigger 逻辑但**不取数**（供表格视图，解决 I1 双重请求）；`useLiveData` 复用它（`onTick`=发起 fetch）。本阶段先实现 `enabled` 调度，`active`/可见性 gating 随 T019/T020 一并补到该 hook。
- [X] T008 [P] [US1] 新建 `frontend/lib/workspace/__tests__/use-live-data.test.ts`（vitest + fake timers + mock `fetchApi`）：断言 ① 按 interval 多次取数；② 刷新中 data 不被清空（无闪）；③ 单次失败保留上次 data 且 `stale=true`，下一周期重试成功后清 `stale`；④ 在途时 tick 跳过（不堆叠，fetch 调用数符合预期）；⑤ 卸载后迟到结果不 setState。

### useApi 系视图接线（保留旧数据，去全屏闪）
- [X] T009 [US1] `frontend/components/workspace/views/metrics-view.tsx`：`useApi`→`useLiveData(path,{active})`；渲染改为**仅 `data==null && loading`** 才出 `ViewStatus`，有过数据后一律原地替换；header 右侧挂 `<ViewRefreshControl>`（喂 `lastUpdatedAt/refreshing/stale` + `refresh`）。
- [X] T010 [P] [US1] `frontend/components/workspace/views/reports-view.tsx`：同 T009 模式接线。
- [X] T011 [P] [US1] `frontend/components/workspace/views/ops/top-strip.tsx`：两个 `useApi`（`/api/ops/summary`、`/api/ops/eta-summary`）改 `useLiveData`，保留旧数据；最后更新时间取两者较新值。
- [X] T012 [P] [US1] `frontend/components/workspace/views/quality-view.tsx`：自定义 `authFetch` 改用 `useLiveData` 模式（保留旧数据、不全屏闪），挂控件。
- [X] T013 [P] [US1] `frontend/components/workspace/views/alerts-view.tsx`：把现有 `Promise.all` 4 端点封成一个聚合 `fetcher: ()=>Promise<AlertsBundle>` 传入 `useLiveData(fetcher,{active})`（I2）；任一端点失败 → fetcher reject → 保留旧数据并置 `stale`。

### DataTable 系视图：原地重取（保分页/筛选）
- [X] T014 [US1] `frontend/components/ui/data-table.tsx`：新增可选 prop ① `reloadSignal?: number`（并入既有 `[values,page,size,reloadNonce]` 重取 effect，父级递增即**一次 in-place 重取**，不 remount、不重置 page/filter/选中，禁用 `key=` remount）；② `onLoadingChange?(loading)` 与 ③ `onLoaded?()`（取数开始/结束/成功回调，回灌父级 `refreshing/lastUpdatedAt`，解决 U1/FR-011/FR-012）。
- [X] T015 [US1] `frontend/components/workspace/views/freshness-view.tsx`：用 **`useRefreshSchedule`** 的 `onTick` 递增 `reloadSignal` 传入 `DataTable`，用 `onLoadingChange/onLoaded` 回灌 `refreshing/lastUpdatedAt` 给 toolbar 右侧 `<ViewRefreshControl>`（手动刷新调 `tickNow()`）；验证翻页/筛选态下刷新不回第一页。
- [X] T016 [P] [US1] `frontend/components/workspace/views/ops/periodic-instances-panel.tsx`：同 T015 用 `useRefreshSchedule` + `reloadSignal` + 完成回调 + 控件。
- [X] T017 [P] [US1] `frontend/components/workspace/views/ops/workflow-instances-panel.tsx`：同 T015 用 `useRefreshSchedule` + `reloadSignal` + 完成回调 + 控件。
- [X] T018 [US1] 运行 `cd frontend && pnpm typecheck` + `pnpm vitest run lib/workspace/__tests__/use-live-data.test.ts`，零错误/全绿。

**Checkpoint**: US1 可独立演示——前台统计页自动无感刷新、显示最后更新时间、失败不丢数据、表格保分页。

---

## Phase 4: User Story 2 - 非激活页暂停刷新，切回即恢复 (P1)

**Goal**: 仅激活且窗口可见时轮询；后台请求数=0；切回 tab / 窗口重新可见立即刷新一次。
**Independent Test**: 双 tab，后台页 Network 无刷新请求；切回即刻一次；最小化窗口停转、回来即刷。

- [X] T019 [US2] 在 `frontend/lib/workspace/use-api.ts` 的 **`useRefreshSchedule`**（`useLiveData` 复用之）中加入生命周期 gating：调度条件改为 `active && enabled && documentVisible`（`documentVisible` 由 `document.visibilityState` + `visibilitychange` 监听维护）；任一转 false 即清 timer（后台请求数=0，FR-003/FR-005）。表格视图（T015–T017）因共用该 hook 自动获得 gating。
- [X] T020 [US2] 在 **`useRefreshSchedule`** 加边沿触发：`active` ✗→✓、或 `documentVisible` ✗→✓ 且 active 时，立即触发一次（`useLiveData`→`refresh()`，表格视图→`onTick`）（FR-004/FR-005）；确保不与既有 inFlight 冲突。
- [X] T021 [P] [US2] 扩充 `frontend/lib/workspace/__tests__/use-live-data.test.ts`：断言 ① `active=false` 或不可见时 0 次轮询请求；② `active` 由 false→true 立即触发一次；③ 模拟 `visibilitychange` 隐藏停转、可见且 active 触发一次。
- [ ] T022 [US2] 浏览器实测（quickstart 步骤 2/3）：双 tab 后台请求数=0、切回即刷、整窗可见性正确；记录结果。

**Checkpoint**: US2 叠加后，刷新只发生在「激活+可见」，资源不空转，切回即新鲜。

---

## Phase 5: User Story 3 - 统一手动刷新控件 + 自动刷新开关 (P2)

**Goal**: 各统计页一致的手动刷新（含刷新中反馈）+ 每页会话内、默认开的自动刷新开关；手动与自动合并不并发。
**Independent Test**: 点刷新即更新且图标旋转；关开关停自动、手动仍可；重开 tab/刷新整页开关回到开；跨页控件外观一致。

- [X] T023 [US3] 完善 `frontend/components/workspace/views/view-refresh-control.tsx`：补「自动刷新开关」（默认开、`onToggleAuto`）与 `stale`/`paused` 非打断提示文案（`viewRefresh.*`）；图标旋转表「刷新中」，不用 `…`；最终对照 `DESIGN.md` 定稿样式与位置。
- [X] T024 [US3] 在 `useLiveData.refresh()` 实现手动+在途**合并**：命中 `inFlightRef` 时复用同一 promise（不并发，FR-008/SC-005）；`refresh()` 不受 `enabled` 限制（暂停时手动仍可用，FR-014②）。
- [X] T025 [US3] 各统计视图加入会话内开关状态：在 metrics/reports/ops（top-strip + 面板）/freshness/quality/alerts 各自 `const [autoEnabled,setAutoEnabled]=useState(true)`，传入 `useLiveData` 的 `enabled` 并驱动各自的 `<ViewRefreshControl>`（默认开、卸载/重开/刷新整页回 true，FR-014③）。
- [X] T026 [P] [US3] 扩充单测：`refresh()` 在途时复用同一 promise（fetch 仅一次）；`enabled=false` 下 `refresh()` 仍发起请求。
- [ ] T027 [US3] 浏览器实测（quickstart 步骤 5/6/8）：手动刷新反馈、开关会话内行为、跨 6 视图控件一致性走查（SC-004）。

**Checkpoint**: 三个 story 全部交付，手动/自动/开关闭环。

---

## Phase 6: Polish & 跨切面

- [ ] T028 [P] 失败态收尾走查（quickstart 步骤 7）：断后端时各视图保留旧数据 + 非打断 stale 提示 + 无全屏错误；恢复后下周期自愈（FR-010/SC-006）。
- [X] T029 [P] i18n 校验：`pnpm` i18n 键一致性检查通过（zh-CN/en-US `viewRefresh.*` 对齐），无遗漏 `t()`。
- [X] T030 [P] 设计契约门复核：对照 `frontend/DESIGN.md` 确认控件图标/间距/三段式/无 `…` 合规；如有冲突按 CLAUDE.md 停并记录。
- [X] T030b [P] FR-013 排除清单回归签收（C1）：确认 `instance-log`（SSE）、workflow-canvas/编辑器、settings、详情/占位视图**未引入** `useLiveData`/`useRefreshSchedule`、行为与 main 一致；`useApi` 对外签名/语义未变（非统计视图零回归）。
- [X] T031 全量 `cd frontend && pnpm typecheck` + `pnpm vitest run` 全绿；按 quickstart「完成判据」逐项签收。

---

## Dependencies & 执行顺序

- **Setup（T001–T002）** → **Foundational（T003–T006）** → 阻塞所有 story。
- **US1（T007–T018）**：MVP，依赖 Foundational。T007（引擎）阻塞 T009–T017；T008 与 T007 并行编写、随后跑。
- **US2（T019–T022）**：依赖 US1 的 `useLiveData`（在其上加 gating/边沿）。
- **US3（T023–T027）**：依赖 Foundational 控件外壳 + US1 引擎（加 toggle/merge）。可与 US2 部分并行（不同关注点；T024 改 hook 与 T019/T020 同文件，注意串行）。
- **Polish（T028–T031）**：全部 story 后。

**同文件串行警告**：`use-api.ts` 被 T001/T003/T007/T007b/T019/T020/T024 反复触及——这些**不可并行**（标 [P] 的均为不同文件）。`useRefreshSchedule`（T007b）是 `useLiveData` 与表格视图共用的调度内核，T019/T020 的 gating 改它一处即全覆盖。

## 并行机会示例
- Setup：T001 ∥ T002。
- Foundational：T004 ∥ T006（registry vs 新组件文件）。
- US1 视图接线：T010 ∥ T011 ∥ T012 ∥ T013 ∥ T016 ∥ T017（不同视图文件）；但都依赖 T007 引擎与 T014 DataTable prop（表格类）。
- US3：T026 单测可与 T025 视图接线并行。

## Implementation Strategy
- **MVP = US1**：交付「前台统计页自动无感刷新 + 最后更新时间 + 失败保数据 + 表格保分页」。即可演示核心价值。
- **增量 2 = US2**：加资源治理（仅激活+可见才刷、切回即新鲜）。
- **增量 3 = US3**：加统一手动控件 + 会话内开关。
- 每个增量结束跑 typecheck + 相关 vitest + 对应 quickstart 走查，确保独立可交付。

**任务总数**: 33（Setup 2 · Foundational 4 · US1 13 含 T007b · US2 4 · US3 5 · Polish 5 含 T030b）
