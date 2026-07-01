# Implementation Plan: Dashboard-01 外壳布局迁移（保留多 Tabs）

**Branch**: `034-dashboard-layout-shell` | **Date**: 2026-07-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/034-dashboard-layout-shell/spec.md`

## Summary

现状：`032-project-nav` 已交付「左侧7分组+项目切换+icon rail 收起」的侧边导航，`metrics-view.tsx` 等视图也已具备 dashboard-01 式的统计卡片网格（`grid gap-3 sm:grid-cols-2 lg:grid-cols-4` + 图标/数值/标签卡），`033-ui-table-frame-spacing`（并行分支）把 `DataTable` 统一成组件内建边框 frame。缺口只剩一处：**内容区顶部没有面包屑/上下文行**——用户折叠侧边栏后无法确认当前位置，多个视图切换时也没有统一的"你在哪"提示，这是 dashboard-01 骨架里 sidebar 之外的另一半（header）。

技术路径：**单点注入面包屑，不逐视图侵入**。在 `workspace.tsx` 的 `WorkspaceTabBar` 与内容渲染区之间插入一个新增的 `WorkspaceBreadcrumb` 组件，其内容纯派生自当前激活 Tab（`view` + `params`）与既有数据源（`nav-groups.ts` 的 `resolveActiveHighlight`/`NAV_GROUPS`、`views.ts` 的 `VIEW_META`、`project-context.ts` 的项目名），不引入新状态、不新增后端接口、不改 `store.ts` 的 Tab 状态机（FR-005 硬约束）。其余視圖只做**审计**：确认卡片区块已符合 `metrics-view.tsx` 建立的栅格惯例、`DataTable` 版式已符合 033 的 frame 规范，不新建图表可视化（FR-008）。

## Technical Context

**Language/Version**: TypeScript 5 / React 19 / Next.js 16（App Router, Turbopack）

**Primary Dependencies**: shadcn/ui（base style）、Tailwind v4（`@theme inline` 语义 token）、hugeicons、next-intl、zustand（既有 `useWorkspaceStore`，不新增 store）；**无新增依赖**（FR-008 明确排除图表库）

**Storage**: N/A（纯前端，面包屑为无状态派生渲染，不落库、不进 `WorkspaceSnapshot`）

**Testing**: vitest（面包屑派生函数的纯函数单测，类比 `nav-groups.test.ts` 现有套路）+ Playwright 浏览器验证门（多 Tab 切换/侧栏折叠/双主题下的面包屑与卡片栅格目测）

**Target Platform**: Web（桌面视口为主），Frontend `:4000`

**Project Type**: Web application（仅 `frontend/`，不动 `backend/`）

**Performance Goals**: 面包屑为 O(1) 查表计算（`viewToGroup`/`VIEW_META` 均为构建期生成的 `Record`），Tab 切换新增渲染开销可忽略；不引入新网络请求

**Constraints**: 严守 `frontend/DESIGN.md`——① 「布局：无分割线」条款，面包屑行与 Tab 条、内容区之间**不加 `border-b`/`border-t`/`<Separator>`**，靠 padding/背景层次区分；② 只用已登记语义 token，不写裸色值、不手写 `dark:`；③ FR-008/FR-009 明确不引入图表库、不新增未登记样式体系

**Scale/Scope**: 1 个新增组件（`WorkspaceBreadcrumb`）+ `workspace.tsx`/`tab-bar.tsx` 2 处最小编辑 + 全项目 ~18 个视图组件（`views/*.tsx` 及 `views/ops/*.tsx`、`views/asset/*.tsx`）的卡片栅格/表格版式**审计**（预期多数零改动，个别对齐 `metrics-view.tsx` 惯例）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

宪法（`.specify/memory/constitution.md` v1.2.0）五原则均为 Weft "Tasks-as-Code" 转型的服务端/CLI/AI 归位约束，与本纯前端外壳布局特性正交。逐条核对：

- **I. Files-First** — 不涉及任务/工作流的文件表示。✅ 无冲突
- **II. Server is Source of Truth** — 不触 pull/push/隔离/版本快照；面包屑不引入任何持久化状态。✅ 无冲突
- **III. Two-Legged Debugging** — 不触 CLI runtime/executor。✅ 无冲突
- **IV. AI Lives in Local Agent** — 不引入服务端 AI；不损伤运行态观测（ops overview/metrics/run logs/DAG views）——面包屑只是这些视图之上的一层导航上下文提示，不改其数据/行为。✅ 无冲突
- **V. Reuse the Kernel** — 不触调度/执行/PolicyEngine/MCP；前端侧同样"复用而非重写"——面包屑数据源全部复用 `032` 已交付的 `nav-groups.ts`/`views.ts`/`project-context.ts`，零新建配置表。✅ 无冲突

**额外治理门（CLAUDE.md）**：
- **Design Contract Gate** — 已先读 `frontend/DESIGN.md` 并声明约束（见 Technical Context / Constraints）。面包屑行是**布局结构新增**（非 theme/颜色变更），需遵守既有「无分割线」条款；不改 YAML tokens、不改 `globals.css`。
- **Post-Edit Verification** — 每次 `frontend/` 改动后 `pnpm typecheck` 零错误。
- **跨 feature 意识（CLAUDE.md 并行隔离条款）** — 与并行分支 `033-ui-table-frame-spacing` 在 `frontend/components/ui/data-table.tsx` 上存在潜在文件级相邻（本特性不改该文件本身，仅审计调用点版式），实现阶段需在合并前对齐 033 最新状态，避免两者的表格版式判断基线不一致。

**结论**：Constitution Check **PASS**，无违规、无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/034-dashboard-layout-shell/
├── plan.md              # 本文件
├── research.md          # Phase 0：面包屑挂载点 + 数据源 + 无分割线视觉处理决策
├── data-model.md         # Phase 1：面包屑路径为无状态派生模型，无持久化实体
├── quickstart.md         # Phase 1：多 Tab 切换 + 侧栏折叠 + 双主题浏览器验证脚本
├── contracts/
│   └── breadcrumb-visual-contract.md   # Phase 1：面包屑与卡片/表格栅格的可验收视觉契约
├── checklists/
│   └── requirements.md  # /speckit-specify 已生成
└── tasks.md              # /speckit-tasks 生成（本命令不产出）
```

### Source Code (repository root)

```text
frontend/
├── components/workspace/
│   ├── workspace.tsx                  # ★ 核心改动：WorkspaceTabBar 之后插入 <WorkspaceBreadcrumb />
│   ├── breadcrumb.tsx                 # ★ 新增：WorkspaceBreadcrumb —— 纯派生渲染，读 activeTabId 对应 tab
│   ├── tab-bar.tsx                    # 小改：现有 tabLabel() 由模块私有函数改为 export，供 breadcrumb 复用
│   ├── left-nav.tsx                   # 不变，仅作为 resolveActiveHighlight 用法参照
│   └── views/
│       ├── metrics-view.tsx           # 标杆：现有卡片栅格即 dashboard-01 参照样板，预期零改动
│       ├── fleet-view.tsx             # 审计：既有 grid 用法是否需对齐标杆
│       ├── reports-view.tsx           # 审计：Card 用法
│       ├── settings-view.tsx          # 审计：与 033 的间距改动是否有交叠，以 033 合入后基线为准
│       ├── datasources-view.tsx       # 审计：DataTable 调用点版式
│       ├── freshness-view.tsx         # 审计：DataTable 调用点版式
│       ├── ops-view.tsx / ops/*.tsx   # 审计：面板卡片 + DataTable 调用点
│       ├── asset-catalog-view.tsx / asset/*.tsx   # 审计
│       ├── metric-marketplace-view.tsx / metric/*.tsx  # 审计
│       ├── lineage-view.tsx / lineage/*.tsx       # 审计
│       └── 其余（alerts/event-center/quality/workflow-canvas/instance-log/workflow-instance-detail/placeholder） # 审计：确认无强行插入空卡片区块（FR-010）
├── lib/workspace/
│   ├── nav-groups.ts                  # 不变：面包屑分组标题数据源（resolveActiveHighlight/NAV_GROUPS）
│   ├── views.ts                       # 不变：面包屑视图标题数据源（VIEW_META）
│   └── store.ts                       # 不变：Tab 状态机不受影响（FR-005 硬约束）
├── lib/project-context.ts             # 不变：面包屑项目名数据源
└── DESIGN.md                          # 若面包屑视觉版式需要新约定，同步增补一段（不改颜色 token）
```

**Structure Decision**：单一前端工程（Web application，仅 `frontend/`）。变更以**单点注入**为主轴——面包屑逻辑集中在 1 个新组件 + `workspace.tsx`/`tab-bar.tsx` 的最小编辑，不逐视图侵入；~18 个视图文件仅作**审计**（预期多数零改动），卡片/表格版式已有 `metrics-view.tsx`（栅格）与 033 交付的 `DataTable`（边框 frame）两个既定标杆可对齐，本特性不重新发明版式规范。

## Complexity Tracking

> 无 Constitution 违规，本节留空。
