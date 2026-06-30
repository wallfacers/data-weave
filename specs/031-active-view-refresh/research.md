# Phase 0 Research: 激活页自动无感刷新 + 统一手动刷新

所有决策均基于 worktree 内 `origin/main` 基线代码勘察（非被污染的主工作副本）。无 NEEDS CLARIFICATION 遗留（两点已在 spec clarify 解决：开关=每页/会话内/默认开；周期=统一 ~30s）。

## D1. 激活信号如何传给视图

- **Decision**：在 `workspace.tsx` 渲染处把 `active={tab.id === activeTabId}` 作为 prop 传入 `<View>`；`ViewProps` 增加 `active?: boolean`。
- **Rationale**：`workspace.tsx` 已经计算 `tab.id === activeTabId`（用于 `flex/hidden`），就地多传一个布尔零成本；视图不必各自订阅 store、也不必知道自己的 tabId（视图实例与 tab 一一对应，但视图当前拿不到 tabId）。prop 下传比「视图内 `useWorkspaceStore(s => s.activeTabId)` + 自知 tabId」更简单、更易测。
- **Alternatives**：① 视图订阅 store 比较 activeTabId——需把 tabId 也传进来，多一层；② Context 注入 active——对单一布尔过重。

## D2. 轮询能力的形态：扩展 useApi vs 新 hook

- **Decision**：新增 `useLiveData<T>(path, opts)`，把 `useApi` 内的 fetch+auth+401+解包逻辑抽成共享 `fetchApi<T>(path)` 复用；`useApi` 保持原样（非统计视图不受影响）。
- **Rationale**：`useApi` 语义是「一次性、refetch 即清空为 null」，与无感刷新（保留旧数据）冲突；直接改 useApi 会波及所有调用方（爆炸半径大）。新 hook 隔离风险，旧 hook 零回归。
- **Alternatives**：给 useApi 加一堆 options 开关——签名臃肿、易误用；引入 SWR/React-Query——新依赖、与现有 authFetch/ApiResponse 解包风格不一致，过重。

## D3. 无感更新（不闪 / 不重置）

- **Decision**：`useLiveData` 区分 `loading`（首屏、无任何数据时为真）与 `refreshing`（已有数据、后台重取中为真）。refetch 成功才整体替换 data；失败不动 data。视图渲染规则改为：**仅当 `data == null && loading`** 才显示 `ViewStatus`/骨架；一旦有过数据，后续刷新一律原地替换，绝不回退到全屏 loading。
- **Rationale**：直接满足 FR-002/FR-010。当前各视图 `if (!data) return <ViewStatus/>` 的写法在 refetch 清空时会闪全屏——换 hook + 保留旧数据即根治。
- **Alternatives**：保留 useApi 但加「上一帧缓存」——等于重写 hook，不如直接做。

## D4. 去重 / 不堆叠 / 手动+自动合并

- **Decision**：hook 内持 `inFlightRef`（当前在途 promise 或 null）。
  - 周期 tick：若 `inFlightRef` 非空→**跳过本次**（不堆叠，满足 FR-009）。
  - 手动 `refresh()`：若 `inFlightRef` 非空→**返回/复用同一在途 promise**（合并，不并发，满足 FR-008）；否则发起新请求并写入 inFlightRef，settle 后清空。
  - 每次发起带 `alive`/`generation` 标记，组件卸载或 path 变化丢弃迟到结果（防 setState-after-unmount 与错页写入，满足边界「刷新中被切后台」）。
- **Rationale**：单一 inFlight 闸门同时解决堆叠与并发去重，逻辑集中、可单测。
- **Alternatives**：AbortController 取消旧请求——也可，但「合并复用」比「取消重发」更省请求且语义更稳；可作为卸载时的补充（abort 在途）。

## D5. 窗口级可见性

- **Decision**：叠加 `document.visibilityState`，监听 `visibilitychange`。调度条件 = `active && enabled && visible`。窗口从隐藏转可见且本视图 active→边沿触发一次立即 `refresh()`（满足 FR-005）。
- **Rationale**：tab 级 active 只解决「工作区内切换」；整窗最小化/切走需可见性 API 才能停转、回来才刷新。
- **Alternatives**：仅依赖 active——窗口后台时仍空转，违反 FR-005/SC-003。

## D6. DataTable 视图的无感刷新（保留分页/筛选）

- **Decision**：复用 `DataTable` 既有的 `reloadNonce` 机制——它在 `[values, page, size, reloadNonce]` 变化时**原地重取**（不 remount、不重置 page/filter）。向上**暴露受控 reload 入口**：给 `DataTable` 增加可选 `reloadSignal?: number`（父级递增即触发一次 in-place 重取）或 `reloadRef`（imperative handle）。父视图用 `useLiveData` 的调度节拍驱动该信号；首屏数据沿用 DataTable 自身 fetch。
- **Rationale**：DataTable 已实现「无感重取」，只差一个对外触发口。**绝不**采用 datasources 的 `key={reloadKey}` remount 法——那会丢分页/滚动/选中，违反 FR-002。
- **Alternatives**：把分页/筛选 state 提升到父层自管——大改 DataTable 调用方，超范围。`reloadSignal` 是最小侵入。
- **Open（留给 tasks 实现期定稿）**：`reloadSignal` prop vs `useImperativeHandle` ref——倾向 `reloadSignal: number`（更声明式、易测）。

## D7. 统一控件位置与样式（DESIGN.md 约束）

- **已读 DESIGN.md 并采纳的约束**：
  - 结构化列表一律 `DataTable` + `DataTableToolbar`，三段式布局（toolbar `shrink-0` / 表格 `flex-1` / 分页 `shrink-0`），不得整 tab 一起滚（§表格规范）。→ 控件放在 toolbar 行右侧，不破坏三段式。
  - 图标用 hugeicons `RefreshIcon`（已是 `freshness`/catalog-tree 既用图标），`size-4`，ghost 按钮，语义 token（`text-muted-foreground` / `hover:bg-accent`）。
  - 无 `…` 表「进行中」（CLAUDE.md）——刷新中用图标旋转 + disabled，不用省略号。
- **Decision**：新增 `<ViewRefreshControl>` 统一渲染【最后更新时间｜刷新按钮｜自动刷新开关】。
  - 卡片型视图（metrics/reports/quality/alerts）：放视图 header 右侧（与标题同排）。
  - 表格型视图（freshness/ops 实例面板）：放 `DataTableToolbar` 右侧。
  - 控件本身在所有视图视觉一致（同组件、同图标、同间距），满足 FR-007/SC-004。
- **开关 UI**：小号 switch 或带文案的 toggle（「自动刷新」），默认开；DESIGN.md 走查时定稿具体形态。
- **Conflict 处理**：若 DESIGN.md 与最终摆放冲突——按 CLAUDE.md 设计契约门，停下询问；当前判断无冲突（toolbar 右侧操作区是既有惯例）。

## D8. 自动刷新开关的状态载体（会话内 / 默认开）

- **Decision**：视图内 `const [autoEnabled, setAutoEnabled] = useState(true)`，作为 `useLiveData` 的 `enabled` 与控件的受控值。
- **Rationale**：精确满足 FR-014——默认开；暂停只停自动、手动仍可用（`refresh()` 不看 enabled）；卸载/重开 tab/刷新整页时组件重挂→回到 `true`，天然「会话内不持久化」。
- **Alternatives**：放 zustand store / localStorage——会变成跨会话持久或全局，违反澄清结论。

## D9. 刷新周期常量

- **Decision**：单一共享常量 `LIVE_REFRESH_INTERVAL_MS = 30_000`，所有纳入视图统一（澄清结论）。集中一处便于日后调整或个别页覆盖。
- **Rationale**：FR-001 + SC-001（≤30s）。
- **Alternatives**：按页分档——已被澄清否决（先统一）。

## D10. 测试策略

- **单测（vitest，hook 纯逻辑）**：用假定时器 + mock fetch 验证：激活→启动轮询；失活/隐藏→停止且请求数=0；切回→立即刷新一次；在途时 tick 跳过（不堆叠）；手动+在途→合并（不并发）；失败→保留旧 data 且置 stale，下周期重试；卸载→丢弃迟到结果。
- **浏览器实测**：双 tab 观测后台请求数=0；metrics 后端变更后 ≤30s 自动可见且滚动/不闪；freshness 翻页+筛选后自动刷新不回第一页；关开关→停自动、手动仍可；跨视图控件外观一致。
- **i18n**：新增 `viewRefresh.*` 键，zh-CN/en-US 键集一致（CI 校验）。
