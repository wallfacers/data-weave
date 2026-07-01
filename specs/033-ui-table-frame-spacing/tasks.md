---
description: "Task list for 033-ui-table-frame-spacing"
---

# Tasks: 系统设置间距统一 + 全站表格边框包裹

**Input**: Design documents from `/specs/033-ui-table-frame-spacing/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ui-visual-contract.md, quickstart.md

**Tests**: 本特性为**纯 CSS/版式变更**，仓库现无 jsdom/RTL/vitest-DOM 组件渲染基建（现有测试均为 `lib/*.test.ts` 纯逻辑）。为该视觉改动新建 DOM 渲染基建属得不偿失的范围蔓延，故**不写 vitest DOM 测试**；按 constitution「no test = not done」与 CLAUDE.md 浏览器门，**验证以 Playwright 浏览器门为一等公民**（跨页 + 亮/暗双主题目测契约 A/B）+ `pnpm typecheck` + `pnpm design:lint`。见 [contracts/ui-visual-contract.md](contracts/ui-visual-contract.md) 的自动/人工判定划分。

**Organization**: 按用户故事分组。关键依赖：**边框 frame 是 `DataTable` 组件内建的共享改动（Foundational），两个故事都依赖它**——settings 删 Card 后靠它保留边框，全站表格靠它统一。故 Foundational 必须先落。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1=设置间距 / US2=全站表格边框
- 每条含精确文件路径（均在 `frontend/`）

---

## Phase 1: Setup（前置门）

**Purpose**: 守 Design Contract Gate + 确认基线绿

- [ ] T001 读 `frontend/DESIGN.md`（尤其「布局：无分割线」「用法约束」「数据表格 — DataTable」三段）并声明采纳约束：只用语义 token（`border`/`bg-card`/`rounded-xl`）、间距走 `gap-*`/`p-*` 刻度、禁手写 `dark:` 覆盖、图标走 hugeicons；随后 `cd frontend && pnpm typecheck` 确认改动前基线零错误

---

## Phase 2: Foundational（阻塞两个故事的共享改动）

**Purpose**: 把「边框包裹」下沉为 `DataTable` 组件内建外观——此改动一落，全站表格（含设置页）自动获得统一 frame

**⚠️ CRITICAL**: 本阶段完成前，US1 / US2 均不可开工（US1 删 Card 依赖此改动保留边框；US2 的全站统一即此改动的传播）

- [ ] T002 在 `frontend/components/ui/data-table.tsx` 根 `<div>`（现 `flex min-h-0 min-w-0 flex-1 flex-col gap-3`，约 L214）追加边框 frame class：`rounded-xl border bg-card overflow-hidden`，使 toolbar+表体+分页三段被同一条语义色边框包裹、四角与横向滚动区被圆角裁剪（契约 A1/A3/A5，FR-006/007/010）
- [ ] T003 同文件 `frontend/components/ui/data-table.tsx`：给 `DataTableToolbar` 段与 `Pagination` 段补水平内边距（量级对齐参照 `p-4` 家族，如 `px-3`；表头 `border-b` 与数据行**保持满幅贴边框**，勿给根统一 padding），避免控件贴边；像素值以浏览器门定档（契约 A2，FR-008；依赖 T002，同文件顺序改）
- [ ] T004 [P] 增补 `frontend/DESIGN.md`「数据表格 — DataTable」段：记录"表格外观统一由组件内建 `rounded-xl border` 边框容器包裹（toolbar+表+分页三段同框），调用点不再自行套边框卡片"为规范；**不改** YAML tokens、不改 `globals.css`（Design Contract Gate 要求设计系统变更先落真相源）

**Checkpoint**: `DataTable` 现自带边框——打开任一含表格视图，表格已被单框包裹；可分头进入 US1 / US2

---

## Phase 3: User Story 1 - 系统设置间距统一（Priority: P1）🎯 MVP

**Goal**: 消除 settings 双层内边距，Tab 条/标题/表格左边缘对齐，三 Tab 同构、切换零跳动

**Independent Test**: 打开系统设置→切换用户/角色/项目三 Tab：内容区四周留白一致、无双层 padding、切换时表格左上角不跳动（契约 B）

### Implementation for User Story 1

- [ ] T005 [US1] 在 `frontend/components/workspace/views/settings-view.tsx` 的 `SettingsView`（约 L652-690）移除 `Card` / `CardContent p-4` 包裹层（连带清理 `Card`/`CardContent` 的 import，若他处无用），让三 Tab 组件直接渲染在内容区（frame 已由 DataTable 内建，无需外层卡片供边框；契约 B1/A6，FR-002/011，SC-003/006）
- [ ] T006 [US1] 同文件：`SettingsView` 外层容器保留**单一** `p-4`（现 `flex flex-1 flex-col gap-4 p-4`，L657），确保 Tab 条、各 Tab `<h2>`、`DataTable` 三者左边缘对齐；核对纵向 `gap` 取统一刻度（外层 `gap-4` 与各 Tab 内 `gap-3` 的关系收敛为一致语义，勿留随意值；契约 B2/B5，FR-001/003/005，SC-001）
- [ ] T007 [US1] 同文件：核对 `UsersTab`/`RolesTab`/`ProjectsTab` 三者容器结构完全同构（均 `flex flex-col gap-*` + `<h2>` + `DataTable`，L250/415/584），消除任一 Tab 独有的额外边距，保证切换零跳动（契约 B3/B4，FR-004，SC-002）
- [ ] T008 [US1] 浏览器门验证 US1：登录→系统设置→三 Tab 切换，逐条对 [contracts/ui-visual-contract.md](contracts/ui-visual-contract.md) 契约 B（B1 单层留白 / B2 左边缘对齐 / B3 同构 / B4 零跳动 / B5 统一 gap）；参照 [quickstart.md](quickstart.md) §3.1

**Checkpoint**: US1 独立可验——设置三 Tab 间距一致、切换零跳动、单层边框

---

## Phase 4: User Story 2 - 全站表格边框包裹（Priority: P1）

**Goal**: 以设置项目 Tab 为参照，全站 8 个含表格视图 100% 呈现统一边框 frame、无双层边框、亮/暗双主题可辨

**Independent Test**: 逐一打开各含表格页面，表格均被单一边框卡片包裹，边框/圆角/内留白与「设置·项目 Tab」参照一致（契约 A、C）

> 说明：T002/T003 落地后，以下 7 站**预期零改动**，任务本质为**审计确认**（无双层边框、圆角裁剪正常、布局未破）；仅当发现某站自带 `border`/`Card` 与新 frame 叠框或父容器约束致渲染异常时才动该文件。均为不同文件，可并行 [P]。

### Implementation for User Story 2

- [ ] T009 [P] [US2] 审计 `frontend/components/workspace/views/freshness-view.tsx`（DataTable 外为 `flex-1 overflow-hidden` 容器 L184）：确认 `rounded-xl` 边框在该父容器内正确裁剪、无双框、`border-b` 头部与表格 frame 不冲突（契约 A4/A5/C1）
- [ ] T010 [P] [US2] 审计 `frontend/components/workspace/views/datasources-view.tsx`（`p-4` 容器 L258）：确认无双框、frame 观感符合参照（契约 A6/C1）
- [ ] T011 [P] [US2] 审计 `frontend/components/workspace/views/ops/periodic-instances-panel.tsx`（`p-5` 容器 L373，标杆表）：确认 frame 观感即参照基准（契约 A/C1）
- [ ] T012 [P] [US2] 审计 `frontend/components/workspace/views/ops/periodic-workflows-panel.tsx`（`p-5` L204）：确认无双框、frame 正常（契约 A6/C1）
- [ ] T013 [P] [US2] 审计 `frontend/components/workspace/views/ops/manual-workflows-panel.tsx`（`p-5` L159）：确认无双框、frame 正常（契约 A6/C1）
- [ ] T014 [P] [US2] 审计 `frontend/components/workspace/views/ops/workflow-instances-panel.tsx`（`p-5` L276）：确认无双框、frame 正常（契约 A6/C1）
- [ ] T015 [P] [US2] 审计 `frontend/components/workspace/views/ops/backfill-panel.tsx`（`p-5` L206）：**区分**其内另有的 `rounded-lg border bg-muted/30` 提示块（L226，非 DataTable）勿误动；确认表格 frame 与提示块并存不混淆（契约 A6/C1/C2）
- [ ] T016 [US2] 跨页 + 亮/暗双主题浏览器门：对 8 个含表格视图逐条核 [contracts/ui-visual-contract.md](contracts/ui-visual-contract.md) 契约 A2/A4/A5/A6 + C1，抽 3 页目测与参照一致（SC-005）、切换主题验边框可辨（SC-007）、全站无双框（SC-006）；参照 [quickstart.md](quickstart.md) §3.2/§3.3

**Checkpoint**: US2 独立可验——全站表格统一边框、无双框、双主题可辨

---

## Phase 5: Polish & Cross-Cutting

**Purpose**: 收口静态门与全量验证

- [ ] T017 [P] `cd frontend && pnpm typecheck` 零错误 + `pnpm design:lint` 通过（DESIGN.md 增补段不破结构）
- [ ] T018 跑 [quickstart.md](quickstart.md) 全量验证清单（§1-§4）；若边框/间距不显先 `rm -rf frontend/.next` 重启 `pnpm dev` 再验（Turbopack 陈旧 CSS 缓存坑），确认 8 表全覆盖（SC-004）
- [ ] T019 [P] 复核 `view-refresh-control.tsx` 等无 `<DataTable>` 调用点确未被误纳入（契约 C2 范围边界）；确认无遗留冗余 import / 无 `dark:` 手写覆盖

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Setup；**阻塞 US1 与 US2**
- **US1 (Phase 3)**: 依赖 Foundational（删 Card 后靠内建 frame 保留边框）
- **US2 (Phase 4)**: 依赖 Foundational（全站统一即 frame 传播的审计）
- **Polish (Phase 5)**: 依赖 US1 + US2

### 关键顺序

`T002 → T003`（同文件 `data-table.tsx`，顺序）；`T004` 可与之并行。
Foundational 完成后，US1（T005→T006→T007→T008，同文件顺序 + 验证）与 US2（T009-T015 并行审计 → T016 汇总验证）**可并行推进**。

### Within Each Story

- US1：T005（删 Card）→ T006（统一 padding）→ T007（三 Tab 同构）→ T008（浏览器验）——同文件，严格顺序
- US2：T009-T015 不同文件并行审计 → T016 跨页汇总浏览器验

### Parallel Opportunities

- Phase 2：T004 [P] 与 T002/T003 并行
- Phase 4：T009-T015 七站审计全部 [P] 并行
- Phase 5：T017、T019 [P] 并行
- 若双人：Foundational 落地后，A 做 US1、B 做 US2

---

## Parallel Example: User Story 2 审计

```bash
# T009-T015 七个不同文件，无相互依赖，可同时下发：
Task: "审计 freshness-view.tsx —— frame 圆角裁剪 + 无双框"
Task: "审计 datasources-view.tsx —— 无双框"
Task: "审计 ops/periodic-instances-panel.tsx —— 参照基准观感"
Task: "审计 ops/periodic-workflows-panel.tsx —— 无双框"
Task: "审计 ops/manual-workflows-panel.tsx —— 无双框"
Task: "审计 ops/workflow-instances-panel.tsx —— 无双框"
Task: "审计 ops/backfill-panel.tsx —— 区分内部 rounded-lg 提示块"
```

---

## Implementation Strategy

### MVP First

1. Phase 1 Setup → 2. Phase 2 Foundational（DataTable frame，**核心**）→ 3. Phase 3 US1（设置间距）→ **STOP & VALIDATE**：设置三 Tab 一致即 MVP 可 demo。

### Incremental

Foundational 落地即已让全站表格上框（隐性满足 US2 大半）；US1 收口设置间距为可 demo MVP；US2 的审计 + 双主题验证把「所有页面」的保证钉死。Polish 收静态门。

### 兜底 / Review（供 human + 兜底 AI）

- 每个 `frontend/` 改动后 `pnpm typecheck`；关键视觉过 Playwright 浏览器门（亮/暗各一遍）。
- Review 重点：① `data-table.tsx` 是否只用语义 token、无 `dark:` 手写；② settings 是否真删 Card 且无双框、三 Tab 同构；③ 7 站审计是否有被漏改成双框的；④ `backfill-panel` 内部提示块是否被误当表格边框动过；⑤ `DESIGN.md` 增补是否与实现一致、`design:lint` 绿。

---

## Notes

- [P] = 不同文件、无依赖；[Story] 映射到 US1/US2 便于追溯
- 无后端/数据模型/API/主题色变更；不改 `globals.css`、不改 `schema.sql`
- 不新建 jsdom/RTL 基建——纯 CSS 变更以浏览器门为验证一等公民
- 每完成一任务或一逻辑组即提交；任一 checkpoint 可停下独立验证
- 避免：给 DataTable 根统一 padding（会让表头 border-b 内缩）、误动 `backfill-panel` 内部提示块、手写 `dark:` 颜色覆盖
