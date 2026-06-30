# Data Model: 激活页自动无感刷新（前端状态模型，无库表）

本特性无后端实体、无数据库表。以下是前端 hook/组件的状态契约。

## 1. `useLiveData<T>` —— 生命周期感知的取数 hook

### Signature（fetcher 优先，解决多端点/聚合）
```ts
function useLiveData<T>(
  fetcher: () => Promise<T>,          // 取数闭包；alerts 等多端点在此 Promise.all 聚合
  opts: { active?: boolean; enabled?: boolean; intervalMs?: number; deps?: unknown[] },
): LiveDataState<T>
// 便捷重载：path-string 形态内部包成 fetcher = () => fetchApi<T>(path)
function useLiveData<T>(path: string, opts?: {...}): LiveDataState<T>
```
- `fetcher` 形态覆盖 **alerts 4 端点 `Promise.all` 聚合**（I2）；单端点视图仍可传 `path` 字符串。
- `deps`：fetcher 闭包依赖（如 filters）变化时重建并重取，等价于 path 变化。

### Options
| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `active` | `boolean` | — | 本视图是否为当前激活 tab（由 workspace 下传） |
| `enabled` | `boolean` | `true` | 自动刷新开关（视图内 session state） |
| `intervalMs` | `number` | `30000` | 统一刷新周期 |
| `deps` | `unknown[]` | `[]` | fetcher 闭包依赖；变化即重建并重取 |

### Return —— `LiveDataState<T>`
| 字段 | 类型 | 说明 |
|---|---|---|
| `data` | `T \| null` | 最近一次**成功**的数据；刷新中**不清空** |
| `loading` | `boolean` | 仅首屏（`data==null` 且首次请求在途）为真 |
| `refreshing` | `boolean` | 已有数据、后台重取中为真（驱动控件图标旋转） |
| `error` | `boolean` | 最近一次请求是否失败 |
| `stale` | `boolean` | 当前展示的 data 是否「可能过时」（最近刷新失败时为真，成功即清） |
| `lastUpdatedAt` | `number \| null` | 最近一次成功刷新的时间戳（ms）；驱动「最后更新时间」 |
| `refresh` | `() => Promise<void>` | 手动刷新；与在途请求合并，不并发 |

### 内部状态（非导出）
- `inFlightRef: Promise<void> | null` —— 在途闸门（去重 + 不堆叠）。
- `generationRef: number` —— 每次 path 变化/卸载自增，丢弃迟到结果。
- `documentVisible: boolean` —— 由 `visibilitychange` 维护。

### 调度真值表（是否运行 interval 轮询）
| active | enabled(开关) | documentVisible | 行为 |
|---|---|---|---|
| ✗ | * | * | 停（清 timer），后台请求数=0 |
| ✓ | ✗ | * | 停自动；手动 `refresh()` 仍可 |
| ✓ | ✓ | ✗ | 停（整窗后台）；可见时不空转 |
| ✓ | ✓ | ✓ | 运行 ~30s 周期轮询 |

### 边沿触发（立即刷新一次）
- `active` 由 ✗→✓（切回该 tab）。
- `documentVisible` 由 ✗→✓ 且 `active`（窗口重新可见）。
- `enabled` 由 ✗→✓（用户重新打开开关，可选立即刷新一次——实现期定）。

### 并发/堆叠规则
- 周期 tick：`inFlightRef != null` → 跳过（不堆叠，FR-009）。
- `refresh()`：`inFlightRef != null` → 复用同一 promise（合并，FR-008）；否则新建并登记，settle 后清空。
- 结果回写前校验 `generation` 未变、组件仍挂载，否则丢弃（防错页写入/卸载后 setState）。

### 成功/失败迁移
- 成功：`data=新值; lastUpdatedAt=now; error=false; stale=false; refreshing=false`。
- 失败：`data 保持; error=true; stale=true; refreshing=false`（loading 永不因失败回真，除非从未成功过）。下一周期自动重试。

## 1b. `useRefreshSchedule` —— 仅调度 hook（表格视图用，不自取数）

DataTable 视图（freshness / ops 周期实例 / ops 流实例）的**数据由 `DataTable` 自己 fetch**，若再用 `useLiveData` 会造成**双重请求**（I1）。故抽出「仅节拍、不取数」的调度 hook，复用 `useLiveData` 内部同一套调度/可见性/edge-trigger 逻辑（实现上 `useLiveData` 可基于 `useRefreshSchedule` + fetcher 组合而成）。

```ts
function useRefreshSchedule(
  onTick: () => void,                 // 每次该刷新时回调（父级据此递增 reloadSignal）
  opts: { active?: boolean; enabled?: boolean; intervalMs?: number },
): { tickNow: () => void }            // 手动触发一次（供手动刷新按钮）
```
- 调度真值表与 edge-trigger 同 `useLiveData`（见下），仅把「发起 fetch」替换为「调用 `onTick`」。
- 不持有 `data`；`lastUpdatedAt`/`refreshing` 由 DataTable 的完成回调（§4）回灌给控件。

## 2. `RefreshControlState` —— 统一控件 props（`<ViewRefreshControl>`）
| prop | 类型 | 说明 |
|---|---|---|
| `lastUpdatedAt` | `number \| null` | 展示「最后更新时间」（相对或绝对，i18n） |
| `refreshing` | `boolean` | true→刷新图标旋转 + 按钮 disabled |
| `stale` | `boolean` | true→以非打断方式提示「数据可能过时/更新失败」 |
| `autoEnabled` | `boolean` | 自动刷新开关受控值（默认 true） |
| `onToggleAuto` | `(next: boolean) => void` | 切换开关 |
| `onRefresh` | `() => void` | 触发手动刷新 |

视觉契约（DESIGN.md）：`RefreshIcon` size-4、ghost、语义 token；刷新中旋转不用 `…`；开关默认开；卡片型放 header 右侧、表格型放 `DataTableToolbar` 右侧；跨视图同一组件保证一致（FR-007/SC-004）。

## 3. `ViewProps` 扩展
```ts
export interface ViewProps {
  params?: Record<string, unknown>
  active?: boolean   // 新增：是否当前激活 tab
}
```
`workspace.tsx`：`<View params={tab.params} active={tab.id === activeTabId} />`。

## 4. DataTable reload 接线契约
- 新增可选 prop：
  - `reloadSignal?: number` —— 父级每递增一次，`DataTable` 触发**一次 in-place 重取**（并入既有 `[values,page,size,reloadNonce]` effect），**不 remount、不重置 page/filter/选中**。
  - `onLoadingChange?: (loading: boolean) => void` —— 取数开始/结束回调，驱动控件 `refreshing`（U1/FR-012）。
  - `onLoaded?: () => void` —— 一次取数**成功完成**回调，父级据此更新 `lastUpdatedAt`（U1/FR-011）。
- 父视图（freshness / ops 实例面板）用 **`useRefreshSchedule`** 的 `onTick` 递增 `reloadSignal`，用 `onLoadingChange/onLoaded` 回灌 `refreshing/lastUpdatedAt` 给 `<ViewRefreshControl>`；手动刷新按钮调用 `tickNow()`。
- **禁用** `key={nonce}` remount 法（datasources 现状）——违反无感（FR-002）。

## 5. 常量
```ts
export const LIVE_REFRESH_INTERVAL_MS = 30_000   // 统一周期（FR-001 / SC-001）
```

## 6. i18n 键（messages/{zh-CN,en-US}.json，键集一致）
- `common.refresh`（复用，已存在）
- `viewRefresh.lastUpdated`（如「最后更新 {time}」/相对时间）
- `viewRefresh.auto`（自动刷新开关标签）
- `viewRefresh.paused`（已暂停态）
- `viewRefresh.updateFailed` / `viewRefresh.stale`（更新失败 / 数据可能过时）
