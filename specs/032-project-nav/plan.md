# Implementation Plan: 企业项目左侧导航（按功能模块划分目录）

**Branch**: `032-project-nav` | **Date**: 2026-07-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/032-project-nav/spec.md`

**Isolation**: 本特性在隔离 worktree `/home/wallfacers/project/dw-032-project-nav` 上开发（分支 `032-project-nav`，从 `main` 切）。所有 speckit/编码命令在此 worktree 执行，避免与主副本的 030/031 抢指针。

## Summary

在应用最左侧新增一条**常驻左侧导航栏**：①把全部「可作入口」的功能视图**按 7 个功能模块分目录**呈现，点击即经现有 `useWorkspaceStore.open()` 打开/激活标签页（与「+」菜单、深链 `?open=` 三入口并存、行为一致）；②导航**高亮当前激活功能**（随工作区标签切换实时更新，上下文详情视图归属父模块）；③顶部**企业项目切换器**，列出当前租户下项目，切换后更新全局项目上下文并令按项目取数的视图重新取数；④导航可**收起为仅图标 icon rail**。

技术路径：纯前端增量。新增 `lib/workspace/nav-groups.ts`（分组数据，纯数据无 React）、`lib/project-context.ts`（zustand + localStorage 项目上下文 store，仿 `date-format-store`）、`lib/project-api.ts`（复用既有 `GET /api/projects`），以及 `components/workspace/left-nav.tsx`（导航 UI），在 `app-shell.tsx` 双栏布局最左侧挂载。把现有硬编码 `projectId = 1`/`?? 1` 的 api 调用改读项目上下文 store。**零后端改动**（复用 `/api/projects`），无新建表/端点。

## Technical Context

**Language/Version**: TypeScript 5 / React 19 / Next.js 16（App Router, Turbopack）。后端 Java 25 / Spring Boot 4 —— 仅**复用** `GET /api/projects`，无改动。

**Primary Dependencies**: shadcn/ui（base style）、hugeicons（`@hugeicons/core-free-icons`）、next-intl、zustand、next-themes。

**Storage**: 前端 UI/上下文状态走 `localStorage`（项目上下文 currentProjectId、导航展开/收起偏好），仿 `lib/date-format-store.ts` 同步初始读取（无闪烁）。项目数据来自后端 PostgreSQL 既有 `projects` 表（经 `/api/projects`）。

**Testing**: vitest（store/分组数据/高亮映射纯函数 + 组件）+ 浏览器验证（鉴权见 [[browser-verification-jwt-login]]：admin/admin 取 JWT 注入 `localStorage dw.auth.token`）。

**Target Platform**: 桌面 Web（窄屏经 icon rail 让出空间；移动端专门适配不在本期）。

**Project Type**: Web 前端（既有 frontend/ 单体，无新模块）。

**Performance Goals**: 导航高亮同步当前功能 ≤1s（SC-004）；切换项目后按项目取数视图重新取数 ≤2s（SC-007）；首屏导航无菜单点击即见全景（SC-001）。

**Constraints**: 遵循 `frontend/DESIGN.md` 设计系统（语义 token、`gap-*`/`size-*`、hugeicons、base-style `render` 用法）；区域间留白不用分割线 [[no-header-footer-divider-lines]]；i18n 双语 key 集一致、不硬编码中文；base Button render Link 需 `nativeButton={false}` [[dataweave-frontend-stack]]。

**Scale/Scope**: 18 个视图 / 7 个分组 / 1 条导航栏 + 1 个项目切换器；改动约 4 个新文件 + 修订 `app-shell.tsx`、`catalog-api.ts`、`datasource-api.ts`、`messages/{zh-CN,en-US}.json`。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

宪法 v1.2.0 五原则评估：

| 原则 | 是否触及 | 结论 |
|------|----------|------|
| I. Files-First | 否 | 纯前端 UI，不涉任务/类目文件定义。PASS（N/A） |
| II. Server is Source of Truth + 租户/项目隔离 | **是** | 项目切换器仅列**当前租户**项目（`/api/projects` 服务端按 `TenantContext.tenantId()` 隔离）；切换 `projectId` 仅改前端取数维度，后端各 API 仍按 `tenant_id`(+`project_id`) 强隔离，越权访问由后端拒绝。前端无法选到本租户外项目。**PASS** |
| III. Two-Legged Debugging（CLI runtime） | 否 | 不涉 CLI/执行器。PASS（N/A） |
| IV. AI Lives in Local Agent（无服务端 AI 脑） | 否 | 不新增任何服务端 AI；无 MCP/CLI 写面变化。PASS（N/A） |

**初评结论**：无违规，无需 Complexity Tracking。Phase 1 设计后复评（见末尾）同样 PASS。

## Project Structure

### Documentation (this feature)

```text
specs/032-project-nav/
├── plan.md              # 本文件
├── research.md          # Phase 0：5 项决策（项目列表作用域/持久化/分组数据/切项目重取/高亮映射）
├── data-model.md        # Phase 1：前端实体（NavGroup/NavItem/ProjectContext/NavUiState）+ 复用 Project
├── quickstart.md        # Phase 1：本地起前端 + 浏览器验收脚本
├── contracts/
│   └── projects-list.md # 复用 GET /api/projects 的消费契约（无新端点）
└── tasks.md             # Phase 2（/speckit-tasks 生成，非本命令）
```

### Source Code (repository root)

```text
frontend/
├── components/
│   ├── app-shell.tsx                      # 改：双栏 → 最左挂 <LeftNav/>
│   └── workspace/
│       ├── left-nav.tsx                   # 新：左侧导航 UI（分组目录 + 项目切换器 + 收起）
│       └── left-nav/                       # 新（可选拆分）：project-switcher.tsx / nav-group.tsx
├── lib/
│   ├── workspace/
│   │   ├── nav-groups.ts                  # 新：功能模块分组数据（纯数据，无 React）+ view→module 映射 + 高亮归属
│   │   ├── views.ts                        # 既有：ViewType/VIEW_META（复用，不改）
│   │   ├── registry.tsx                    # 既有：图标 VIEW_RENDER（复用图标）
│   │   └── store.ts                        # 既有：open/activate/closeMany（复用；切项目关详情页用 closeMany）
│   ├── project-context.ts                 # 新：zustand+localStorage 当前项目上下文（currentProjectId）
│   ├── project-api.ts                     # 新：listProjects() 复用 GET /api/projects
│   ├── nav-ui-store.ts                    # 新（或并入 project-context）：导航展开/收起偏好（localStorage）
│   ├── catalog-api.ts                     # 改：projectId 由上下文 store 提供（去硬编码 1）
│   └── datasource-api.ts                  # 改：同上
└── messages/
    ├── zh-CN.json                         # 改：新增 nav 分组标题 + 项目切换器文案（命名空间 leftNav.*）
    └── en-US.json                         # 改：同 key 集
```

**Structure Decision**: 沿用既有 frontend 单体结构，无新目录层级。左侧导航作为 `app-shell.tsx` 双栏 flex 的首个子节点（`<main>` 之前）。分组「数据」与「组件」分离遵循既有约定（`views.ts` 纯数据 / `registry.tsx` React），故 `nav-groups.ts` 纯数据可被 vitest 直接 import 做映射断言。

## Complexity Tracking

> 无宪法违规，本节为空。
