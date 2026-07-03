# Research: 042 产品面收缩

**Date**: 2026-07-03 · **Input**: [spec.md](spec.md) · 方向依据: 方向文档 §8

Technical Context 无 NEEDS CLARIFICATION；本文以"移除清单摸底"取代技术选型研究——删除型特性的全部风险都在"漏删（死代码/死键）"与"错删（共享依赖）"两侧。

## D1. 受影响文件全量清单（grep 实证，非推测）

**Decision**: 移除/修改范围锁定为下表，不越界。

| 动作 | 文件 | 说明 |
|---|---|---|
| 改 | `frontend/lib/workspace/views.ts` | ViewType 删 4 成员；VIEW_META 删 4 项（reports 含 `defaultPinned: true` → PINNED_VIEWS 自动缩为 freshness/metrics）；marketplace 的 `requirePermission: "metric:manage"` 引用随之消失 |
| 改 | `frontend/lib/workspace/registry.tsx` | 删 4 个 VIEW_RENDER 项 + 对应 icon import；`placeholder()` 工厂与 `PlaceholderView` 因 integration/service 是仅有消费者 → 一并删除 |
| 改 | `frontend/lib/workspace/nav-groups.ts` | `assets` 组删 marketplace/integration/service（剩 datasources）；`analytics` 组整组删除（仅含 reports） |
| 删 | `frontend/components/workspace/views/metric-marketplace-view.tsx` | 322 行真实视图 |
| 删 | `frontend/components/workspace/views/reports-view.tsx` | 106 行真实视图 |
| 删 | `frontend/components/workspace/views/metric/`（2 个 dialog） | 仅被 marketplace 视图引用（grep 实证） |
| 删 | `frontend/components/workspace/views/placeholder-view.tsx` | 仅被 placeholder 工厂引用 |
| 删 | `frontend/lib/metric-listing.ts` + `lib/metric-listing.test.ts` | 唯一消费者是 marketplace 视图 |
| 改 | `frontend/app/integration/page.tsx`、`app/service/page.tsx`、`app/metrics/page.tsx` | 见 D3 |
| 改 | `frontend/messages/zh-CN.json` + `en-US.json` | 见 D4 |
| 改 | `frontend/lib/workspace/nav-groups.test.ts`、`nav-permissions.test.ts`、（如涉及）`store.test.ts` | 断言随视图全集收紧 |

## D2. 共享依赖判定——哪些看似相关但 MUST 保留

**Decision**: 以下全部保留不动。

- `frontend/lib/catalog-api.ts`：目录 + 市场共用客户端，被保留的 asset-catalog-view / asset-dialog / subscriptions-dialog 引用（订阅/退订走它）。市场专用函数变死导出也**不删**（FR-008 不碰保留视图行为；清理归入后续 catalog 转型特性）。
- `frontend/components/workspace/views/asset/`：属 catalog 视图。
- `lib/types.ts` 的 `MetricCard`：metrics-view（保留的平台运行指标页）仍在用。
- `lib/subscriptions.ts`、`lib/gate-outcome.ts`、`lib/asset-search-query.ts`：catalog 侧消费者。
- 后端一切（`/api/marketplace/*`、`/api/metrics`、`metric:manage` 权限种子、MCP 工具、schema）：FR-007。

**Rationale**: grep 实证保留视图与被删视图之间无 `open("<removed>")` 交叉跳转，共享面只有 catalog-api.ts 与 MetricCard 两处，均以"保留"化解。

## D3. 遗留 redirect 路由处置

**Decision**: `app/integration/page.tsx`、`app/service/page.tsx`、`app/metrics/page.tsx`（现指向 `/?open=reports`）三个文件改为 `redirect("/")`；不删除路由目录。

**Rationale**: 删目录 = 旧书签 404，违背 SC-002"旧链接 100% 正常进入"；保留指向已删视图的 `/?open=` 参数虽也能靠 store 的 isKnownView 守卫兜底（console.warn 后落默认视图），但显式 `redirect("/")` 无警告噪声、意图清晰。

**Alternatives considered**: ① 删除路由目录（404，拒绝）；② 保持原样靠兜底（留下永久性 warn 噪声与死语义，拒绝）。

## D4. i18n 键删除清单（两 bundle 同步）

**Decision**: 删除 `views.{marketplace,reports,integration,service}`、顶层 `reports.*` 与 `metricMarketplace.*` 命名空间、`leftNav.groups.analytics`、`placeholderView.{descIntegration,descService}`（若 placeholderView 空则整个命名空间删除）。`leftNav.groups.assets` 保留（组仍含 datasources）。

**Rationale**: 项目 CI 校验两 bundle 键集一致且每个 `t("key")` 静态可解析；删除消费者的同时必须删键，否则孤儿键腐烂。

## D5. 快照/深链降级——无需新代码

**Decision**: 不写新的降级逻辑，依赖既有守卫 + 测试确认。

**Rationale**: `store.ts` 已有两道门——`open()` 对未知视图 warn+忽略（L101）、`restore()` 对未知视图静默丢弃并"至少回到 Pinned 底座"（L228 + 文档注释）。ViewType 收缩后旧值自动落入"未知"分支。FR-003/FR-004 的验证以测试用例形式补强（构造含 removed view 的快照断言降级），而非新实现。

## D6. 导航不变量测试联动

**Decision**: `nav-groups.test.ts` 的"入口 ∪ 详情 = VIEW_META 全集"不变量**保持原样**（它引用全集常量，全集缩了断言自动收紧）；显式点名被删视图的断言（`resolveActiveHighlight("reports")`、nav-permissions 中 marketplace/reports/integration/service 用例）改写为保留视图或删除。

**Rationale**: 不变量测试是这次收缩的安全网，动断言输入、不动断言结构。
