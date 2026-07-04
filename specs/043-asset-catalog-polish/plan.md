# Implementation Plan: 资产目录页面规范化重设计

**Branch**: `043-asset-catalog-polish` | **Date**: 2026-07-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/043-asset-catalog-polish/spec.md`

## Summary

将资产目录页面从三栏（分面 | 列表 | 详情）重构为**顶部 filter toolbar + 卡片网格**布局。资产以独立 `AssetCard` 组件呈现（卡片网格），点击卡片原地内联展开详情（`motion` 动画），操作按钮平铺可见。筛选条件改为顶部 toolbar segmented/multiSelect 控件，对齐项目 `DataTableToolbar` 模式。Toast 从自建 setTimeout 迁移到项目统一的 `sonner`。i18n 零硬编码，zh-CN/en-US 双 bundle key 集一致。

## Technical Context

**Language/Version**: TypeScript 5 / React 19 / Next.js 16 (App Router, Turbopack)

**Primary Dependencies**: shadcn/ui (Badge, Button, Dialog, Input, Segmented via FilterDef), hugeicons (Database01Icon 等), motion (Framer Motion, 项目已安装), next-intl, sonner (项目已安装), zustand

**Storage**: N/A（前端；数据经既有 `/api/catalog/*` 端点）

**Testing**: vitest (lib 纯函数单测) + `pnpm typecheck` + `pnpm design:lint` + 浏览器手验

**Target Platform**: 桌面浏览器（工作台 `:4000`），后端 `:8000`

**Project Type**: Web（仅 frontend 侧改动）

**Performance Goals**: 卡片展开/收起动画 ≤200ms 感知延迟；筛选切换 loading 骨架即时出现

**Constraints**: 零后端/schema 改动；闸门零旁路；i18n 双 bundle 同 key 集；复用既有 API client 和 gate-outcome/asset-patch/asset-search-query/subscriptions lib

**Scale/Scope**: 1 个既有视图组件重构 + 1 个新 AssetCard 组件 + 拆分 toolbar 为独立组件 + i18n 补全 ~15 个新 key；资产数据量 < 50 条

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 适用性 | 结论 |
|---|---|---|
| I. Files-First | N/A | 不涉及任务/编目文件定义格式 |
| II. Server is Source of Truth | ✅ 合规 | 纯前端改造，所有读写走既有 `/api/catalog/*`，不引入本地权威态 |
| III. Two-Legged Debugging | N/A | 不涉及 CLI/本地 runtime |
| IV. AI Lives in Local Agent | N/A | 无服务端 AI，无 AI 代码改动 |
| V. Reuse the Kernel — 写经闸门留审计，零旁路 | ✅ 合规 | 所有写操作复用既有 `resolveGate` + `gateToast`，不新增旁路写路径 |
| Dev Workflow — 新特性必带测试 | ✅ 计划内 | vitest 单测 + 浏览器手验 |

**结论**: 无违规。纯前端、复用内核与闸门，与宪法完全一致。

## Project Structure

### Documentation (this feature)

```text
specs/043-asset-catalog-polish/
├── plan.md              # 本文件
├── research.md          # Phase 0：决策记录
├── data-model.md        # Phase 1：前端消费的既有实体
├── quickstart.md        # Phase 1：本地起栈 + 闭环手验脚本
├── contracts/           # Phase 1：既有 REST 端点契约（只读文档）
│   └── asset-catalog.md
├── checklists/
│   └── requirements.md  # 已存在（specify 阶段）
└── tasks.md             # Phase 2（/speckit-tasks 生成）
```

### Source Code (仅 frontend)

```text
frontend/
├── components/
│   ├── ui/
│   │   └── (复用既有 Badge / Button / Dialog / Input / LoadingState / Pagination)
│   └── workspace/
│       └── views/
│           ├── asset/
│           │   ├── asset-card.tsx             # 新建：资产卡片（含内联展开详情）
│           │   ├── asset-form-dialog.tsx      # 保持，微调 i18n
│           │   ├── asset-filter-toolbar.tsx   # 新建：筛选 toolbar（segmented + search）
│           │   └── subscriptions-dialog.tsx   # 保持，微调 i18n
│           ├── asset-catalog-view.tsx          # 重构：三栏 → toolbar + 卡片网格
│           └── shared/
│               └── confirm-dialog.tsx          # 保持
├── lib/
│   ├── catalog-api.ts          # 保持（已有 17 个端点封装）
│   ├── gate-outcome.ts         # 保持（三态分流 + toast 文案）
│   ├── asset-patch.ts          # 保持（PATCH-diff）
│   ├── asset-search-query.ts   # 保持（查询构建器）
│   └── subscriptions.ts        # 保持（订阅判定）
└── messages/
    ├── zh-CN.json              # 补 ~15 个 assetCatalog key
    └── en-US.json              # 补 ~15 个 assetCatalog key
```

**Structure Decision**: 新建 `asset-card.tsx`（卡片 + 内联展开）+ `asset-filter-toolbar.tsx`（toolbar 筛选），重构 `asset-catalog-view.tsx` 为 toolbar + 卡片网格。零后端文件改动。

## Complexity Tracking

> 无违规，无需填写。
