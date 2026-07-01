# Implementation Plan: 系统设置间距统一 + 全站表格边框包裹

**Branch**: `033-ui-table-frame-spacing` | **Date**: 2026-07-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/033-ui-table-frame-spacing/spec.md`

## Summary

两处纯前端视觉一致性诉求：

1. **系统设置间距统一（故事1）**——`settings-view.tsx` 现有 `外层 div p-4 gap-4` + `Card>CardContent p-4` 双层内边距，导致 Tab 条（仅 `p-4` 内缩）与表格内容（`p-4`+`p-4` 内缩）左边缘不对齐、观感"千奇百怪"。移除冗余 `Card` 包裹层，让 Tab 条、标题、表格共用单一 `p-4` 外边距 + 统一纵向 `gap`，三个 Tab 结构同构、切换零跳动。

2. **全站表格边框包裹（故事2）**——以设置项目 Tab 的带边框卡片为参照，把"边框包裹"下沉为 `DataTable` **组件内建**外观（`rounded-xl border bg-card overflow-hidden` + 三段内边距），而非在每个调用点手工套 `Card`。这样 8 个真实表格调用点**零改动自动统一**，且与 `DESIGN.md`「全项目结构化列表表格只有一种长相」的既定立场一致。settings 现有的 `Card` 包裹层随之移除（否则叠加双层边框，违反 FR-011）。

技术路径核心：**改一处组件（`DataTable` 根容器）达成全站表格统一** + **删一层冗余包裹（settings 的 Card）同时收口两个问题**。无后端、无数据模型、无 API 变更。

## Technical Context

**Language/Version**: TypeScript 5 / React 19 / Next.js 16 (App Router, Turbopack)

**Primary Dependencies**: shadcn/ui (base style), Tailwind v4（`@theme inline` 语义 token）, hugeicons, next-intl；无新增依赖

**Storage**: N/A（纯前端视觉，不触库）

**Testing**: vitest（DOM 层断言 `DataTable` 根含 frame class）+ Playwright 浏览器验证门（跨页目测边框/间距一致，浅/暗双主题）

**Target Platform**: Web（桌面视口为主），Frontend `:4000`

**Project Type**: Web application（仅 `frontend/`，不动 `backend/`）

**Performance Goals**: 纯 CSS class 变更，无运行时开销；HMR/渲染无回归

**Constraints**: 严守 `frontend/DESIGN.md`——只用语义 token（`border`/`bg-card`/`rounded-xl`）、间距用 `gap-*`/`p-*` 刻度、**禁手写 `dark:` 颜色覆盖**、图标走 hugeicons；边框色用语义 `border` token（亮 `oklch(0.922 0 0)` / 暗 `oklch(1 0 0 /10%)`），无需新增主题变量、无需改 `globals.css`

**Scale/Scope**: 1 个共享组件（`DataTable`）+ 8 个含表格视图的容器审计（其中仅 `settings-view` 需删 Card 层）+ 1 处 `DESIGN.md` 规范增补

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

宪法（`.specify/memory/constitution.md` v1.2.0）五原则均为 Weft "Tasks-as-Code" 转型的服务端/CLI/AI 归位约束，与本纯前端外观特性正交。逐条核对：

- **I. Files-First** — 不涉及任务/工作流文件表示。✅ 无冲突
- **II. Server is Source of Truth** — 不触 pull/push/隔离/版本快照。✅ 无冲突
- **III. Two-Legged Debugging** — 不触 CLI runtime/executor。✅ 无冲突
- **IV. AI Lives in Local Agent** — 不引入服务端 AI；不损伤运行态观测（ops overview/metrics/run logs/DAG views）。本特性**改善**这些视图的表格观感、不改其数据与行为。✅ 无冲突
- **V. Reuse the Kernel** — 不触调度/执行/PolicyEngine/MCP；纯前端复用既有 `DataTable`、语义 token，不新造轮子（正是"复用而非重写"的体现）。✅ 无冲突

**额外治理门（CLAUDE.md）**：
- **Design Contract Gate** — 已先读 `frontend/DESIGN.md` 并声明约束（见 Technical Context / Constraints）。本变更为设计系统的**组件版式约定增补**（非 theme/颜色变更），故按门要求需**同步增补 `DESIGN.md` 的「数据表格 — DataTable」段**记录"边框包裹三段同框"为规范，无 `globals.css` 改动。
- **Post-Edit Verification** — 每次 `frontend/` 改动后 `pnpm typecheck` 零错误；关键视觉过 Playwright 浏览器门。

**结论**：Constitution Check **PASS**，无违规、无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/033-ui-table-frame-spacing/
├── plan.md              # 本文件
├── research.md          # Phase 0：frame 归属决策 + 间距归一决策
├── data-model.md        # Phase 1：N/A（无数据实体，占位说明）
├── quickstart.md        # Phase 1：跨页浏览器验证脚本
├── contracts/
│   └── ui-visual-contract.md   # Phase 1：DataTable frame + settings 间距的可验收视觉契约
├── checklists/
│   └── requirements.md  # /speckit-specify 已生成
└── tasks.md             # /speckit-tasks 生成（本命令不产出）
```

### Source Code (repository root)

```text
frontend/
├── components/
│   └── ui/
│       └── data-table.tsx                      # ★ 核心改动：根容器加 frame（rounded-xl border bg-card overflow-hidden）+ 三段内边距
├── components/workspace/views/
│   ├── settings-view.tsx                        # ★ 删 Card/CardContent 包裹层；统一 p-4 单层 + 纵向 gap；三 Tab 同构
│   ├── freshness-view.tsx                        # 审计：DataTable 外 flex-1 overflow-hidden 容器，确认无双边框、圆角正常裁剪
│   ├── datasources-view.tsx                      # 审计：p-4 容器，确认无双边框
│   └── ops/
│       ├── periodic-instances-panel.tsx         # 审计：p-5 容器（标杆表），确认 frame 观感符合参照
│       ├── periodic-workflows-panel.tsx         # 审计
│       ├── manual-workflows-panel.tsx           # 审计
│       ├── workflow-instances-panel.tsx         # 审计
│       └── backfill-panel.tsx                    # 审计（注意其内另有 rounded-lg border 提示块，勿与表格 frame 混淆）
└── DESIGN.md                                    # ★ 增补「数据表格」段：边框包裹为组件内建规范
```

**Structure Decision**：单一前端工程（Web application，仅 `frontend/`）。变更以**共享组件下沉**为主轴——frame 归 `DataTable` 根容器一处，其余 8 个视图仅作**审计确认**（除 `settings-view` 需删冗余 Card 层外，预期零改动）。`view-refresh-control.tsx` 无 `<DataTable` 调用，不在范围内。

## Complexity Tracking

> 无 Constitution 违规，本节留空。
