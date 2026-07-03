# Contract: 监督席队列 UI 面 (043)

## 1. 视图注册与主页切换

| 文件 | 变更 |
|---|---|
| `frontend/lib/workspace/views.ts` | `ViewType` 增 `"incidents"`；`VIEW_META` **第一位**声明 `incidents: { titleKey: "views.incidents", defaultPinned: true }` —— PINNED_VIEWS 变为 `[incidents, freshness, metrics]`，`baseTabs()[0]` 机制自动使 incidents 成为默认激活 tab = 产品主页（零 store 改动） |
| `frontend/lib/workspace/registry.tsx` | `VIEW_RENDER.incidents = { icon: <告警/事故类 hugeicon>, component: IncidentsView }` |
| `frontend/lib/workspace/nav-groups.ts` | incidents 加入首组首位（必须满足 nav-groups.test.ts「入口视图 ∪ 详情视图 == VIEW_META 全集」不变量） |
| `frontend/messages/{zh-CN,en-US}.json` | `views.incidents` + `incidents.*` 命名空间，双 bundle 键集一致（CI 硬门）；无 `…` 表示加载态 |

既有 pinned 快照/降级行为不变：incidents 为 base tab，不进快照、不可关闭。**需同步检查 `store.test.ts` 中对 base tab 数量/首 tab 的既有断言**（042 加过"全删回底座"用例）。

## 2. 队列布局（clarify Q2）

```
┌─ 监督席（incidents view）────────────────────────────┐
│ 活跃 (N)                                    [历史 ▸] │
│ ┌────────────────────────────────────────────────┐  │
│ │ ● HIGH  ods_订单同步 失败(EXIT_NONZERO)   ×3    │  │  ← 紧迫度排序
│ │   影响 7 个下游 · 距最近 SLA 2h40m ▼倒计时      │  │
│ │   [重跑] [静默] [详情]      待审批 1 [批准][驳回]│  │
│ └────────────────────────────────────────────────┘  │
│ ── 近 24 小时已自动解决 (M) ──（视觉降权/折叠区）──── │
│ │ ○ dws_日汇总 已自动愈合 · 14:02 · 查看时间线     │  │
└──────────────────────────────────────────────────────┘
```

- **活跃区**：服务端排序直渲染，不做前端重排；倒计时由 `timeBudgetAt` 客户端计算每秒刷新，过期切换为「已超期 Xm」红色态。
- **已解决区**：降权样式（muted 前景、无动作按钮），用于核查自动愈合（点开看时间线）。
- **历史**：区内入口打开筛选面板（state/signature/时间range，分页）。
- **空态**：活跃区空 = 正向空态文案（「数据管线一切正常」），不是错误态。
- 刷新：15s 轮询（SC-001/SC-003 预算 30s）。

## 3. 卡片剖面 → 数据映射

| 卡片元素 | 数据源 | 缺省态 |
|---|---|---|
| 严重度徽标 | `severity` | — |
| 标题 + 次数 | `title` + `occurrenceCount` | — |
| 爆炸半径 | `blastRadius` | `null` →「血缘不可用」；`0` →「无下游影响」 |
| 时间预算倒计时 | `timeBudgetAt` | `null` →「无 SLA 约束」；过期 →「已超期」 |
| 历史同签名提示 | `priorIncidentCount > 0` 显示「历史发生 N 次」 | 隐藏 |
| 诊断/提案占位 | `diagnosis/proposal` 恒 null → 占位态（「等待运维编队接入」不显示为错误） | — |
| 待审批内联 | `pendingActionCount` + 详情 `actions[]`，批准/驳回调 `/api/approvals/*` | 隐藏 |
| 深链 | `sourceKind/sourceRefId` 经既有 `refKindToView`（TASK→ops 实例、WORKFLOW→workflow 详情等）；有 taskInstance 上下文时可直开 instance-log | — |

## 4. 动作交互契约

- **重跑**：调 `/api/incidents/{id}/rerun`，**按 `outcome` 分流**：EXECUTED → toast 成功 + 卡片转处置中；PENDING_APPROVAL → toast「已生成审批单」+ 卡片出现待审批角标；REJECTED → toast 后端 message（信任后端文案，无硬编码 fallback）。
- **静默**：弹出原因输入（必填），成功后卡片移出活跃区。
- **备注**：详情时间线抽屉内追加。
- 所有 toast 文案走后端 message 或 i18n 键，遵守三规则。

## 5. 深链降级

`/?open=incidents` 为合法深链（新 ViewType 进 isKnownView 白名单自动生效）；旧快照无 incidents 不受影响（base tab 不依赖快照恢复）。

## 6. 验收锚点（quickstart 引用）

- 首开 = incidents tab 激活（SC-004）
- 注入失败 → ≤30s 卡片出现（SC-001）；连续 5 次失败 1 张卡计数 5（SC-002）
- 重跑成功 → ≤30s 自动转已解决区（SC-003），全程 ≤3 次点击（SC-005）
- 有下游+SLA 的任务卡片必显分诊两项（SC-006）
- 同工作流实例 10 任务失败 + SLA 破约 → 新增卡片 ≤1（SC-008）
