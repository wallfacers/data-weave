# Research: 数据新鲜度页面重新设计

**Feature**: 041-freshness-redesign
**Date**: 2026-07-03

## 1. 调度周期人读映射

### Decision
通过 JOIN `workflow_node` + `workflow_def` 获取每个任务所属工作流的 `schedule_type`、`cron` 和 `schedule_interval_ms`，在 Java 后端实现 6 种核心模式的人读转换。前端已有 `cron-format.ts` 的 `humanizeCron()` 处理 3 种模式（每天/工作日/每月），后端扩展为 6 种。

### Rationale
- `task_def` 自身不存储调度信息；调度配置在 `workflow_def` 上，通过 `workflow_node.task_id` 关联
- `workflow_def` 的关键字段：`schedule_type`（MANUAL/CRON/DEPENDENCY）、`cron`（VARCHAR(128)）、`schedule_interval_ms`（BIGINT）
- 种子数据中的 cron 模式均为 `0 MM HH * * ?`（每天固定时间）
- 前端 `cron-format.ts` 已处理 3 种模式，后端扩展为 6 种覆盖 FIXED_RATE 场景

### Alternatives Considered
- 前端 `humanizeCron` 复用（客户端计算）→ 信息不足——前端拿不到 `schedule_interval_ms` 且无法处理后端未 JOIN 的场景
- 全量 Cron 解析引擎（如 cron-utils）→ 过度工程，引入额外依赖

## 2. FreshnessSnapshotJob 调度集成

### Decision
使用 Spring `@Scheduled(cron = "0 0 2 * * ?")` 在 master 模块新增 `FreshnessSnapshotJob`，通过 PG advisory lock（`pg_try_advisory_lock`）实现多 master 单次执行。

### Rationale
- 现有 `CronScheduler` 使用 `@Scheduled(fixedRateString)` + `TriggerEngine` 模式，但那是为 workflow cron 触发设计的，不适用于系统级定时任务
- `@Scheduled` 在每个 master 实例上都会执行——需要应用层排他锁防止重复拍摄
- PG advisory lock 是最轻量的方案：`SELECT pg_try_advisory_lock(42)` — 第一个 master 拿到锁执行，其他 skip
- `cron_fire` 表防重是针对 workflow 粒度的，拍快照无需引入新 guard 表

### Alternatives Considered
- Redis SETNX 分布式锁 → 额外依赖 Redis，但项目已有 Redis，也是可行选项
- 注册为系统 workflow → 不必要；快照不是用户可见的任务
- Quartz 调度器 → 额外依赖，过度工程

## 3. 火花图 SVG 渲染

### Decision
纯内联 SVG，在 `freshness-sparkline.tsx` 中手写 `<svg>` 元素，零依赖。自适应数据点数量（1-7 个），当天点实心、历史点空心。

### Rationale
- 避免引入 recharts/d3/chart.js 等图表库——火花图仅需折线+点，SVG 原生支持
- chart-2 青绿色系（`oklch(0.6 0.118 184.704)` 亮 / `oklch(0.696 0.17 162.48)` 暗）适合数据健康语义
- 需要暗色模式适配：使用 CSS 变量 `var(--chart-2)` 或 Tailwind `text-chart-2`

### Alternatives Considered
- Recharts Sparkline → 不必要的大依赖
- Canvas → SVG 更适合小尺寸 + CSS 变量

### Implementation Pattern
```tsx
// Size: 64x20, viewBox="0 0 64 20"
// Points: 1-7 data points, Y-axis mapped: NEVER=16, STALE=12, AGING=8, FRESH=4
// Line: cubic bezier or polyline with strokeWidth=1.5
// Today's point: r=2, filled; Historical: r=1.5, stroke-only
// Color: text-chart-2 (via currentColor)
```

## 4. ResourceBar 提取

### Decision
将 `fleet-card.tsx` 中的 `ResourceBar` 提取到 `components/ui/resource-bar.tsx`，增加 `threshold` prop（默认 90），让新鲜度健康度进度条复用。

### Rationale
- 现有 ResourceBar 是 fleet-card.tsx 内的私有组件，不可复用
- 唯一差异：fleet 用 `value >= 90` 为 destructive；freshness 用 `freshPct < 70` 为 warning
- 提取时增加 `threshold` 和 `highIsBad` prop 控制颜色逻辑

### Implementation Pattern
```tsx
interface ResourceBarProps {
  label: string
  value: number          // 0-100
  icon?: IconSvgElement
  threshold?: number     // default 90
  highIsBad?: boolean    // default true (high=destructive); false for freshness (low=warning)
}
```

## 5. DataTable 外部筛选控制

### Decision
采用外挂方案——在 `fetcher` URL 中额外拼接 `externalTier` 查询参数，绕过 DataTable 内部筛选器限制。

### Rationale
- DataTable 筛选状态由内部 `useState` 管理，**不暴露 ref 或命令式 API**——外部无法程序化修改已挂载 DataTable 的筛选器
- `defaultFilters` 仅用于初始值；mount 后失效
- 不改动 DataTable 公共组件（避免影响其他 10+ 个使用者）
- 在 `freshness-view.tsx` 维护 `externalTier` state → Badge/卡片点击更新 → `fetcher` 将值拼入 URL

### Implementation Pattern
```typescript
const [externalTier, setExternalTier] = useState<string | null>(null)

// fetcher 内:
if (externalTier) qs.set("tiers", externalTier)

// Badge onClick:
<span onClick={(e) => {
  e.stopPropagation()
  setExternalTier(prev => prev === r.tier ? null : r.tier)
}}>
  {tierBadge(r.tier, t)}
</span>
```

## 6. SVG 火花图颜色方案

### Decision
在 SVG 属性中直接引用 CSS 变量 `var(--chart-1)`，利用 CSS 自定义属性级联自动适配亮/暗主题。

### Rationale
- 项目设计系统已定义 `--chart-1` 到 `--chart-5` 双主题变量
- `globals.css` 将 chart 变量映射为 Tailwind `--color-chart-*` token
- SVG 原生支持 `stroke="var(--chart-1)"` `fill="var(--chart-1)"` 语法
- 亮/暗自动切换，无需 JS 主题探测

### CSS Variable Reference
```tsx
<svg>
  <polyline
    stroke="var(--chart-1)"   // teal: 亮 oklch(0.6 0.118 184) / 暗 oklch(0.696 0.17 162)
    fill="none"
    strokeWidth="1.5"
  />
</svg>
```

## 7. AssetSubscription 新鲜度告警对接

### Decision
复用现有 `AssetSubscriptionService`，以 `targetType="TASK"` + `changeFilter="freshness"` 新增订阅类型。

### Rationale
- `asset_subscription.change_filter` 已明确列出 `"freshness"` 为合法 CSV 值
- `AssetSubscriptionService.notifyChange()` 的 `filterMatches` 已是大小写不敏感的 CSV 匹配
- 通知链路完整：`AlertSignal.Type.ASSET_CHANGED` → `AlertSignalListener` → `AlertRule` → `ChannelDispatcher`（EMAIL/WEBHOOK/DINGTALK/WECOM/FEISHU）
- 需扩展 `target_type` 支持 `"TASK"`（当前 schema 注释仅列 ASSET/METRIC），或使用现有 `targetType="ASSET"` 映射到关联资产

### Implementation Pattern
- 订阅：调用 `AssetSubscriptionService` with `targetType: "TASK"`, `targetId: taskId`, `changeFilter: "freshness"`
- 通知触发：当 freshness tier 降级时（FRESH→AGING 或 AGING→STALE 等），`FreshnessService` 调用 `notifyChange()`
- 前端：toggle 调用 API，图标根据已订阅/未订阅切换

## 8. Schema 版本升级

当前 schema 基线：`0.6.2`（事件中心可读化）。本次新增两张快照表 → 升到 `0.6.3`。
