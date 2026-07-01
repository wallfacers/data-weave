# Phase 1 Data Model: 企业项目左侧导航

**Feature**: 032-project-nav | **Date**: 2026-07-01

本特性以**前端呈现/状态**为主，不新增后端表。下列实体多为前端数据结构（TS 类型）；`Project` 复用后端既有模型（只读消费）。

---

## 1. NavGroup（功能模块 / 目录）— 前端纯数据

`lib/workspace/nav-groups.ts`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `string` | 分组稳定标识（`dev`/`ops`/`alerting`/`governance`/`assets`/`analytics`/`admin`） |
| `titleKey` | `string` | i18n key（`leftNav.groups.<id>`），渲染侧 `useTranslations` 解析 |
| `items` | `ViewType[]` | 该分组下有序的入口功能视图 |

**约束**：
- `NAV_GROUPS` 为有序数组，决定导航分组与组内项的稳定显示顺序（FR-005）。
- `items` 内每个 `ViewType` 必须 ∈ `VIEW_META`（已注册视图）。
- `items` 仅含**入口视图**，不含上下文详情视图（`instance-log`/`workflow-instance-detail`）（FR-007）。
- 覆盖不变量（vitest）：`flatten(NAV_GROUPS.items) ∪ CONTEXT_DETAIL_VIEWS === keys(VIEW_META)`，且 `flatten` 内无重复（SC-003）。

**派生结构**：
- `viewToGroup: Record<ViewType, string>` —— 由 `NAV_GROUPS` 构建，入口视图反查所属 groupId。
- `NAV_ENTRY_VIEWS: Set<ViewType>` —— 所有入口视图集合。
- `CONTEXT_DETAIL_VIEWS: Set<ViewType>` —— `{instance-log, workflow-instance-detail}`。
- `detailViewParent: Record<ViewType, { view?: ViewType; group: string }>` —— 详情视图→父模块高亮归属（两者均 `{ group: "ops" }`）（FR-007/D5）。

---

## 2. NavItem（功能项）— 渲染期组合，非独立存储

由 `ViewType` 在渲染时组合而成，无独立持久化：

| 字段 | 来源 | 说明 |
|------|------|------|
| `view` | `ViewType` | 目标视图标识 |
| `label` | `views.<view>`（i18n） | 复用既有视图标题（FR-003） |
| `icon` | `VIEW_RENDER[view].icon` | 复用既有 hugeicons 图标 |
| `groupId` | `viewToGroup[view]` | 所属分组 |
| `active` | 派生 | 是否为当前激活功能（见 NavUiState 高亮逻辑） |

**交互**：点击 → `useWorkspaceStore.open(view)`（去重激活，FR-004/FR-008）。

---

## 3. ProjectContext（当前项目上下文）— zustand + localStorage

`lib/project-context.ts`（仿 `date-format-store`）

| 字段/方法 | 类型 | 说明 |
|-----------|------|------|
| `currentProjectId` | `number \| null` | 当前所选项目 id；初始从 localStorage 同步读取，无则 null（待 projects 加载后定默认） |
| `projects` | `ProjectVO[]` | 当前租户可访问项目列表（加载后缓存） |
| `status` | `"idle" \| "loading" \| "ready" \| "empty" \| "error"` | 列表加载态（驱动切换器只读/空态，FR-017） |
| `loadProjects()` | `() => Promise<void>` | 调 `listProjects()` 填充；ready 后若 `currentProjectId` 为空/不在列表内 → 取**列表第一个**（稳定排序）作为默认（FR-019） |
| `setProject(id)` | `(id:number)=>void` | 更新 `currentProjectId` + 写 localStorage + 触发切项目副作用（关详情页标签，D4） |

**持久化 key**：`dw.project.current`（localStorage）。
**状态转移**：`idle → loading → ready(≥1) | empty(0) | error`；`ready` 时若持久化值有效则恢复（FR-015），否则取首个（FR-019）。

---

## 4. ProjectVO（项目）— 复用后端 Project（只读消费）

来自 `GET /api/projects`。前端只消费子集：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `number` | 项目 id（作为 `projectId` 取数维度） |
| `name` | `string` | 显示名（切换器/顶部展示） |
| `code` | `string` | 项目 code（可选展示/排序辅助） |
| `status` | `string` | `ACTIVE` 等（可用于过滤/置灰，非本期强需求） |

> 隔离：列表由后端按 `tenant_id` 返回（D1）；前端不做隔离逻辑，仅展示与选择。

---

## 5. NavUiState（导航 UI 偏好）— zustand + localStorage

可并入 `project-context.ts` 或单列 `lib/nav-ui-store.ts`：

| 字段/方法 | 类型 | 说明 |
|-----------|------|------|
| `collapsed` | `boolean` | 导航是否收起为 icon rail（FR-009）；初始从 localStorage 同步读取 |
| `toggleCollapsed()` | `()=>void` | 切换 + 写 localStorage |

**持久化 key**：`dw.nav.collapsed`。

**高亮派生（非存储，渲染期计算）**：
- 输入：`useWorkspaceStore(s => s.activeTabId)` → 解析出 `activeView`。
- 规则（D5）：`activeView ∈ NAV_ENTRY_VIEWS` → 高亮该项 + `viewToGroup[activeView]`；`activeView ∈ CONTEXT_DETAIL_VIEWS` → 高亮 `detailViewParent[activeView]`；否则无高亮。

---

## 关系图

```text
NAV_GROUPS (有序) ──contains──> NavItem(ViewType)
       │                            │ label=views.*  icon=VIEW_RENDER
       │ viewToGroup                │ click → useWorkspaceStore.open(view)
       ▼                            ▼
  高亮归属 <── activeTabId(WorkspaceStore) ── 标签切换实时更新

ProjectContext(currentProjectId) ──drives──> catalog-api/datasource-api 取数维度
       │ setProject → close 详情页标签(closeMany)
       └── loadProjects() <── GET /api/projects (tenant-scoped)
```
