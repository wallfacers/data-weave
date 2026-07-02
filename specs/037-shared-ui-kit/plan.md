# Implementation Plan: 复用优先的公共前端组件契约与目录

**Branch**: `037-shared-ui-kit` | **Date**: 2026-07-02 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/037-shared-ui-kit/spec.md`

## Summary

把散落在 `frontend/` 的既有共享组件收编成**单一权威「公共组件目录」**（落于 `frontend/DESIGN.md` 新增章节），确立**复用优先**约定（实现任何界面原语前先查目录，命中必复用，未命中才新建并回填），并统一 9 类高频原语（滚动条/Tabs/表格/下拉/弹框/业务日期/加载/刷新/卡片内边距）的唯一规范外观与用法。

**技术路径要点**（据现状盘点）：绝大多数原语**已有可复用公共组件**（`TabStrip`、`DataTable`、`DropdownSelect`、`Dialog`、`DwScroll`、`Card`、`LoadingState`、`ViewRefreshControl`、`DatePicker` + `biz-date`/`useFormatDateTime` 工具链），且 033（表格边框）、035（加载转圈）已落 main。本特性因此以**编目 + 治理文档 + 少量收敛/补缺口**为主，而非重造组件：① 在 DESIGN.md 建目录章节与复用优先约定；② 把卡片内容内边距等间距固化为**单一 token 真相源**并去除页面魔法间距；③ 收敛分裂的 Tabs（TabStrip 卡片式 for closable + 下划线式 for 非 closable）——需与未合 main 的 030 协调；④ 产出「覆盖/差距清单」并迁移少数高价值存量违规点作示范；⑤ 文档化 bizDate 默认 + 带时间变体约定与刷新按钮统一位置。

## Technical Context

**Language/Version**: TypeScript 5 / React 19 / Next.js 16 (App Router, Turbopack)

**Primary Dependencies**: shadcn base 风格组件（@base-ui/react）、hugeicons（`@hugeicons/core-free-icons`）、next-intl、zustand、`@google/design.md ^0.2.0`（DESIGN.md lint/export，`pnpm design:lint`）、date-fns、OverlayScrollbars（`DwScroll`）

**Storage**: N/A —— 无后端/DB 改动。目录的"数据"即 `frontend/DESIGN.md` 文本 + 组件源码文件 + 覆盖清单 markdown

**Testing**: `pnpm typecheck`（零错误门）+ `pnpm design:lint`（DESIGN.md 结构校验）+ vitest（收敛/迁移的组件单测）+ Playwright/浏览器验证（跨页外观一致性抽查，覆盖明/暗主题）

**Target Platform**: Web 前端（`frontend/` :4000），单一项目，无后端交互

**Project Type**: Web 前端单项目（设计系统 / 组件治理特性；无 API、无数据模型、无调度/权限内核改动）

**Performance Goals**: N/A（功能性特性；目录为文档态查询，无运行时性能目标）

**Constraints**:
- `frontend/DESIGN.md` 为设计系统单一真相源，改动前必读（已读，约束见下）；主题/token 改动须 `DESIGN.md` + `app/globals.css` 同步，`pnpm design:lint` 通过
- 不引入新 UI 框架（沿用 shadcn base + hugeicons + 语义 token）；base-style 规则：自定义 trigger 用 `render` 非 `asChild`，`Button` 作 `<a>` 需 `nativeButton={false}`，图标用 `HugeiconsIcon`，间距用 `gap-*`/`size-*`，语义 token（`bg-primary`/`text-muted-foreground`），无手写 `dark:` 覆盖
- 无分割线布局偏好（区域靠留白区分，不用 border/Separator 线）——见 DESIGN.md「布局：无分割线」

**Scale/Scope**: ~18 个 view、~21 个既有共享组件、9 类原语需编目；存量迁移按"差距清单增量"（clarify 已定），本特性只迁移少数高价值违规点（如 ops/alerts 页内手写下划线 Tab）作示范

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Weft Constitution v1.2.0 五principle面向 Tasks-as-Code 掉头（A–E），本特性为**纯前端设计系统治理**，与内核正交，但须核对：

| Principle | 适用性与判定 |
|-----------|------|
| I. Files-First | ✅ 契合。目录以**纯文本 DESIGN.md 章节 + 组件源码文件**承载，human/agent 可读、可 diff、可 review——不引入不可 diff 的配置。 |
| II. Server is Source of Truth | ➖ 不适用（无 pull/push、无租户/版本快照改动）。 |
| III. Two-Legged Debugging | ➖ 不适用（不碰 CLI/runtime/executor）。 |
| IV. AI Lives in Local Agent | ✅ 契合且强化。目录约定**沉淀于 DESIGN.md + 任务创作 Skill 可发现的知识层**，正是"agent 知识层渐进披露"精神——让本地 agent 少走弯路，不在服务端加任何 AI。 |
| V. Reuse the Kernel（内核复用而非重写） | ✅ **核心契合**。本特性字面上就是"复用既有组件而非重写"：优先编目/收敛现有 21 个组件，仅在确无可复用时新建并回填。 |
| 质量门（新功能必带测试 / typecheck / 浏览器验证） | ✅ 遵守。收敛/迁移的组件带 vitest + typecheck + 明暗主题浏览器抽查；纯文档改动以 `design:lint` + typecheck 为门。 |

**跨特性边界（宪法「Sub-spec isolation / 不闭环」+ CLAUDE.md 跨特性感知）**：本特性显式收编 sibling——033（✅ main）、035（✅ main）已在 037 基线；**030-unify-tab-styles 未合 main（下划线 `tabs.tsx` 仍在分支）**、034 部分（面包屑已撤回、卡片栅格保留）。**依赖处置**：Tabs 收敛需先协调 030（合入或吸收其下划线组件），否则"下划线 Tab 统一"在 037 内无处落地 = 不闭环。已列为 Phase 0 研究项 R3 与首要风险。

**结论**：无 principle 违反，Constitution Check **PASS**。无需 Complexity Tracking 表。

## Project Structure

### Documentation (this feature)

```text
specs/037-shared-ui-kit/
├── plan.md              # 本文件
├── research.md          # Phase 0：现状盘点 + 决策（含 030 Tabs 收敛研究）
├── data-model.md        # Phase 1：目录条目 / 差距清单的"文档实体"结构（非 DB）
├── quickstart.md        # Phase 1：agent「先查目录→命中复用→未命中新建回填」上手指南
├── contracts/           # Phase 1：目录条目 schema + 复用优先校验清单（本特性无 HTTP API）
│   ├── README.md
│   ├── catalog-entry.schema.md
│   └── reuse-first-checklist.md
├── adoption-inventory.md # 覆盖/差距清单（FR-015；Phase 1 产出初版，实现阶段维护）
└── tasks.md             # Phase 2（/speckit-tasks 生成，非本命令）
```

### Source Code (repository root)

本特性仅触及前端，关键真实路径：

```text
frontend/
├── DESIGN.md                                   # 【核心】新增「## 公共组件目录」章节 + 复用优先约定 + 间距 token 说明
├── app/globals.css                             # 间距/滚动条等 token 与 DESIGN.md 同步（如新增 --card-spacing 说明所需）
├── components/
│   ├── ui/                                      # 基础共享件（编目对象；按需补缺口/收敛）
│   │   ├── tab-strip.tsx                        # 卡片式 Tabs（closable，workspace 主 tab）— 目录条目
│   │   ├── tabs.tsx                             # 下划线式 Tabs（非 closable，页内子 tab）— 需协调 030 落地/吸收
│   │   ├── data-table.tsx / table.tsx           # 表格（033 已统一边框包裹）— 目录条目
│   │   ├── select.tsx                           # DropdownSelect（下拉）— 目录条目
│   │   ├── dialog.tsx                           # 弹框 — 目录条目
│   │   ├── dw-scroll.tsx                        # 滚动区（OverlayScrollbars）— 目录条目
│   │   ├── card.tsx                             # 卡片容器（--card-spacing token）— 间距真相源
│   │   └── date-picker.tsx / calendar.tsx       # 日期选择 — 目录条目
│   └── workspace/
│       ├── shared/loading-state.tsx             # 居中转圈加载（035 已统一）— 目录条目
│       └── views/view-refresh-control.tsx       # 统一刷新控件+位置约定 — 目录条目
└── lib/
    ├── workspace/biz-date.ts                    # yesterdayBizDate 默认业务日期 — 日期条目
    └── date-format-store.ts / hooks/use-format-date-time.ts  # 带时间格式化 — 日期"带时间变体"条目
```

**Structure Decision**: 单一前端项目（无 backend option）。核心可交付物是 `frontend/DESIGN.md` 的新章节（单一真相源）+ 少量组件收敛/补缺口 + `specs/037-shared-ui-kit/adoption-inventory.md` 差距清单。既有 `components/ui/` 与 `components/workspace/` 目录布局不变，本特性只**编目 + 收敛**其内容，不重构目录结构。

## Complexity Tracking

> Constitution Check 无违反，无需填写。
