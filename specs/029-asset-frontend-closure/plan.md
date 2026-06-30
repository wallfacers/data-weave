# Implementation Plan: 资产目录 / 指标市场前端收口

**Branch**: `029-asset-frontend`（隔离 worktree `/home/wallfacers/project/dw-029-asset-frontend`） | **Date**: 2026-06-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/029-asset-frontend-closure/spec.md`

## Summary

把 023 已交付的**只读**资产目录 / 指标市场前端补成**写侧闭环**：在既有两个工作台视图（`asset-catalog-view` / `metric-marketplace-view`）内补齐资产 CRUD（创建/编辑/下线/对账）、指标上架/下架、订阅退订、分面真过滤/分页/复用防环提示、质量过滤（被动透传 + 静态声明）。

技术方针：**纯前端增量**，零后端 / schema 改动（FR-013/SC-007）。所有写操作复用 023 已实装的 17 个 REST 端点；写经服务端统一审批闸门（前端只按返回的 `GateResult.outcome` 三态分流）。新表单/确认统一用既有 shadcn `Dialog`；不引新依赖。

## Technical Context

**Language/Version**: TypeScript 5 / React 19 / Next.js 16（App Router, Turbopack）

**Primary Dependencies**: 既有栈——shadcn/ui（base style，已装 `dialog`/`select`/`input`/`checkbox`/`button`/`badge`/`pagination`）、hugeicons、next-intl、zustand。**不新增依赖。**

**Storage**: N/A（前端；数据经既有 `/api/catalog/*` + `/api/marketplace/*` + `/api/datasources` + `/api/metrics`）

**Testing**: vitest + React Testing Library（API client 的 URL/method/body 断言 + Dialog 表单交互）；`pnpm typecheck` / `pnpm design:lint` / i18n:lint 闸门；浏览器手验全闭环（admin/admin 注 JWT，后端 h2 profile）

**Target Platform**: 桌面浏览器（工作台 `:4000`），后端 `:8000`

**Project Type**: Web（仅 frontend 侧改动）

**Performance Goals**: N/A（交互式 CRUD，无吞吐目标）；列表分页默认 size=20，沿用后端 HARD_CAP/truncated 语义

**Constraints**: 零后端/schema 改动；闸门零旁路（写一律经服务端 gate，前端不旁路）；三态如实（不把 PENDING_APPROVAL 伪装成功）；i18n 两 bundle 同 key 集；不用 `…` 表「进行中」

**Scale/Scope**: 2 个既有视图改造 + 1 个 API client 扩展（`lib/catalog-api.ts`）+ 若干 Dialog 子组件；约 14 个缺口（4 用户故事，P1×2 / P2×2）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 适用性 | 结论 |
|---|---|---|
| I. Files-First | N/A | 不涉及任务/编目文件定义格式 |
| II. Server is Source of Truth | ✅ 合规 | 前端只读写服务端,不引入本地权威态;隔离/租户由服务端身份解析,前端不旁路 |
| III. Two-Legged Debugging | N/A | 不涉及 CLI/本地 runtime |
| IV. AI Lives in Local Agent | N/A | 无服务端 AI,无 AI 代码改动 |
| V. Reuse the Kernel — **写经闸门留审计,零旁路** | ✅ 合规（关键） | 所有写操作走既有 `/api/catalog`·`/api/marketplace` → 服务端 `GatedActionService`/PolicyEngine + `agent_action`;前端仅按 `GateResult.outcome` 分流,**不**新增任何旁路写路径 |
| Dev Workflow — worktree 隔离 | ✅ 已执行 | 029 在独立 worktree `dw-029-asset-frontend`,与外部 agent 的 024/028 互不污染 |
| Dev Workflow — 新特性必带测试 | ✅ 计划内 | vitest + RTL + 浏览器手验(见 Testing) |

**结论**：无违规,无需 Complexity Tracking。纯前端、复用内核与闸门,与宪法完全一致。

## Project Structure

### Documentation (this feature)

```text
specs/029-asset-frontend-closure/
├── plan.md              # 本文件
├── research.md          # Phase 0：决策记录（Dialog/分页/picker 数据源/三态/错误码映射）
├── data-model.md        # Phase 1：前端消费的既有实体 + 表单字段契约
├── quickstart.md        # Phase 1：本地起栈 + 闭环手验脚本
├── contracts/           # Phase 1：本特性消费的既有 REST 端点契约（只读文档,非新增 API）
│   ├── asset-catalog.md
│   └── metric-marketplace.md
├── checklists/
│   └── requirements.md  # 已存在（specify 阶段）
└── tasks.md             # Phase 2（/speckit-tasks 生成,非本命令）
```

### Source Code (repository root，仅 frontend)

```text
frontend/
├── lib/
│   ├── catalog-api.ts                  # 扩展：补 updateAsset/reconcileAsset/unsubscribe/
│   │                                   #       listSubscriptions/listMetric/delistMetric;
│   │                                   #       AssetSearchParams 扩 status/qualityMin
│   ├── datasource-api.ts               # 复用（创建资产的数据源选择器数据源）
│   └── (GET /api/metrics 经既有 metrics 封装/types.MetricCard)  # 上架指标的定义选择器
├── components/workspace/views/
│   ├── asset-catalog-view.tsx          # 改造：创建/编辑/下线/对账 Dialog;分面真过滤;
│   │                                   #       分页;质量过滤入口+静态声明;订阅内联+「我的订阅」聚合面板
│   └── metric-marketplace-view.tsx     # 改造：上架/下架 Dialog;复用 Dialog(防环提示+consumerType);
│                                       #       certification 分面;分页
├── components/workspace/views/asset/   # 新增：本特性的 Dialog 子组件（拆分,避免单文件过大）
│   ├── asset-form-dialog.tsx           # 创建+编辑共用（PATCH 语义:仅传改动字段）
│   ├── asset-confirm-dialog.tsx        # 下线/对账/退订 确认（可复用通用确认）
│   └── subscriptions-dialog.tsx        # 「我的订阅」聚合清单 + 退订
├── components/workspace/views/metric/  # 新增
│   ├── metric-listing-dialog.tsx       # 上架（指标定义 Select）
│   └── metric-reuse-dialog.tsx         # 复用（consumerType + consumerRef + 防环错误提示）
├── lib/gate-outcome.ts                 # 新增：GateResult 三态分流 helper（两视图复用）
└── messages/{zh-CN,en-US}.json         # assetCatalog/metricMarketplace 命名空间补 key（两 bundle 同集）

frontend/**/*.test.ts(x)                # vitest + RTL：API client + Dialog 交互 + 三态/防环
```

**Structure Decision**：仅改 `frontend/`。把每个写操作的 Dialog 拆成 `views/asset/*` 与 `views/metric/*` 下的小组件（单一职责、可独立测试），避免两个 view 主文件膨胀（现 asset-view ~260 行、marketplace-view ~194 行,内联会过载）。三态分流抽 `lib/gate-outcome.ts` 复用。API 扩展集中在既有 `lib/catalog-api.ts`,镜像后端端点。

## Complexity Tracking

> 无 Constitution 违规,本节不适用。
