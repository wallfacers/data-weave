# Implementation Plan: 产品面收缩——移除中台货架页面

**Branch**: `042-product-surface-shrink` | **Date**: 2026-07-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/042-product-surface-shrink/spec.md`

## Summary

按方向文档 §8 裁决，从前端工作区移除四个中台货架视图（marketplace 指标市场、reports 报表、service 数据服务、integration 数据集成）：视图组件、注册表、导航入口、遗留 redirect、双语 i18n 键一体删除；历史快照与深链靠既有 `isKnownView` 守卫优雅降级（补测试不写新逻辑）。纯前端删除型特性，服务端零改动。

## Technical Context

**Language/Version**: TypeScript（Next.js 16 App Router + React 19，前端项目既有栈）

**Primary Dependencies**: next-intl（i18n 键集 CI 一致性约束）、zustand（workspace store 快照/恢复）、vitest（既有测试）

**Storage**: N/A（浏览器 localStorage 快照仅在恢复路径被动降级，不改存储格式）

**Testing**: vitest（`lib/workspace/*.test.ts` 断言联动）+ `pnpm typecheck` + i18n 键集一致性检查 + 浏览器验证

**Target Platform**: Web（现代浏览器）

**Project Type**: Web application（本特性只触 `frontend/`）

**Performance Goals**: N/A（删除型特性，无新性能面）

**Constraints**: FR-007 服务端零改动；FR-008 保留视图行为不变；两 i18n bundle 键集必须一致（CI 硬门）

**Scale/Scope**: 视图全集 18 → 14；导航入口 16 → 12；删除 2 个真实视图 + 2 个占位视图 + 4 个附属文件；3 个 redirect 路由改向；2 个语言包若干键；3 个测试文件断言联动

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 说明 |
|---|---|---|
| I. Files-First | ✅ 不涉及 | 不触碰任务/工作流的文件表示 |
| II. Server is the Source of Truth | ✅ 不涉及 | 服务端零改动（FR-007）；不动 pull/push/隔离 |
| III. Two-Legged Debugging (NON-NEG) | ✅ 不涉及 | 不触 CLI/本地运行时/执行器 |
| IV. AI Lives in the Local Agent (NON-NEG) | ✅ 不涉及 | 不动 MCP 面与 Skill；纯 UI 收缩 |
| V. Reuse the Kernel | ✅ 不涉及 | 不新建执行/调度路径 |

**Gate 结论**: PASS（删除型前端特性，五原则均不触及）。Phase 1 设计后复评：仍 PASS——设计未引入任何新面。

## Project Structure

### Documentation (this feature)

```text
specs/042-product-surface-shrink/
├── plan.md              # This file
├── research.md          # Phase 0：移除清单摸底 + 6 项决策（D1–D6）
├── data-model.md        # Phase 1：视图注册模型变更
├── quickstart.md        # Phase 1：验证指南
├── contracts/
│   └── ui-surface.md    # Phase 1：收缩后 UI 面契约（导航清单/深链/快照降级）
└── tasks.md             # Phase 2（/speckit-tasks 产出，非本命令）
```

### Source Code (repository root)

```text
frontend/
├── app/
│   ├── integration/page.tsx        # 改：redirect("/")
│   ├── service/page.tsx            # 改：redirect("/")
│   └── metrics/page.tsx            # 改：redirect("/")（原指向 /?open=reports）
├── components/workspace/views/
│   ├── metric-marketplace-view.tsx # 删
│   ├── reports-view.tsx            # 删
│   ├── placeholder-view.tsx        # 删（仅 integration/service 使用）
│   └── metric/                     # 删（listing/reuse 两个 dialog）
├── lib/
│   ├── metric-listing.ts           # 删（+ metric-listing.test.ts）
│   └── workspace/
│       ├── views.ts                # 改：ViewType/VIEW_META 删 4 项
│       ├── registry.tsx            # 改：VIEW_RENDER 删 4 项 + placeholder 工厂删除
│       ├── nav-groups.ts           # 改：assets 组缩 1 项、analytics 组删除
│       ├── nav-groups.test.ts      # 改：断言联动
│       ├── nav-permissions.test.ts # 改：断言联动
│       └── store.test.ts           # 改/增：removed-view 快照降级用例
└── messages/
    ├── zh-CN.json                  # 改：删键（research D4 清单）
    └── en-US.json                  # 改：删键（同上，键集一致）
```

**Structure Decision**: 只触 `frontend/`；`backend/`、`cli/`、schema、MCP 全部不动（FR-007）。保留的共享面（`lib/catalog-api.ts`、`views/asset/`、`MetricCard` 类型）在 research D2 中逐一判定并锁定不动。

## Complexity Tracking

无违例，无需填写。
