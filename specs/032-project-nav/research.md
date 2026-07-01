# Phase 0 Research: 企业项目左侧导航

**Feature**: 032-project-nav | **Date**: 2026-07-01

本阶段消解 plan 的 NEEDS CLARIFICATION 与 spec 标注的 Deferred 项。结论以 Decision / Rationale / Alternatives 三段记录。

---

## D1. 项目列表的作用域：租户级 vs 成员级

**Decision**：本期采用**租户级**项目列表 —— 直接复用 `GET /api/projects`（无参形态返回 `findByTenantId(tenantId)`），「用户可访问项目」=「当前租户下的项目」。后端 `ProjectController.list` 已按 `TenantContext.tenantId()`（JWT 解析）隔离。成员级（`project_members`）过滤作为**文档化后续项**，本期不做。

**Rationale**：① 既有端点与隔离模型以 `tenant_id` 为边界，零后端改动即满足 FR-016 的「不跨租户暴露」硬要求；② `ProjectMemberRepository` 虽存在但 `list` 未用它过滤，引入成员级过滤需改后端查询 + 测试，超出「左侧导航」核心；③ 当前部署多为单租户少项目，租户级足够。

**风险/缓解**：同租户内用户可见其非成员项目 —— 在隔离语义内（同租户），非越权；若后续需成员级收口，仅需在 `list` 加 `JOIN project_members WHERE user_id=?`，前端契约不变。已在 spec FR-016 注脚与本决策记录，便于后续闭环。

**Alternatives considered**：成员级过滤（更精准但需后端改动+测试，本期不必要）；前端按成员关系二次过滤（拿不到成员数据、且不应在前端做隔离）。

---

## D2. 前端状态持久化：localStorage vs 后端快照

**Decision**：项目上下文（`currentProjectId`）与导航展开/收起偏好走 **`localStorage`**，仿 `frontend/lib/date-format-store.ts`（zustand + 同步初始读取，首屏无闪烁）。不接入后端 workspace 快照表。

**Rationale**：① 与既有前端偏好持久化一致（theme、date-format、auth token 均 localStorage [[dataweave-frontend-stack]]）；② 项目上下文是「本机会话偏好」，无需跨设备同步；③ 零后端改动，符合本期 scope。满足 FR-015/FR-009 的「刷新后记住」。

**Alternatives considered**：后端 workspace snapshot（store 已有 snapshot/restore 通道，但那是 tab 恢复用，混入项目上下文会耦合且需后端字段，过度）；URL query 持久化（刷新可保留但分享链接会泄露/串项目，且与深链 `?open=` 冲突）。

---

## D3. 功能模块分组数据的承载形式

**Decision**：新增 `lib/workspace/nav-groups.ts`（纯数据，无 React 依赖），导出：① `NAV_GROUPS`：有序数组 `{ id, titleKey, items: ViewType[] }`；② `viewToGroup`：`ViewType → groupId` 反查表（构建期生成）；③ `NAV_ENTRY_VIEWS`：作为导航入口的视图集合（排除上下文详情视图）。分组标题 i18n key 命名空间 `leftNav.groups.*`；功能项名称**复用** `views.<viewType>`（既有 `VIEW_META.title`）。图标复用 `registry.tsx` 的 `VIEW_RENDER[view].icon`。

分组（FR-002 + spec 建议表，落定）：
| groupId | titleKey | items（ViewType，有序） |
|---------|----------|--------------------------|
| `dev` | leftNav.groups.dev | `workflow-canvas` |
| `ops` | leftNav.groups.ops | `ops`, `metrics`, `fleet`, `freshness` |
| `alerting` | leftNav.groups.alerting | `alerts`, `event-center` |
| `governance` | leftNav.groups.governance | `catalog`, `quality`, `lineage` |
| `assets` | leftNav.groups.assets | `marketplace`, `datasources`, `integration`, `service` |
| `analytics` | leftNav.groups.analytics | `reports` |
| `admin` | leftNav.groups.admin | `settings` |

**入口排除**（FR-007）：`instance-log`、`workflow-instance-detail` 不入 `NAV_ENTRY_VIEWS`（仅上下文跳转打开）。覆盖校验：`NAV_ENTRY_VIEWS` ∪ {两个详情视图} 必须 == `VIEW_META` 全集（vitest 断言，保证 SC-003 无遗漏/无重复）。

**Rationale**：与既有「数据/组件分离」约定一致（`views.ts` 纯数据），纯数据文件可被 vitest 直接 import 做覆盖性/映射断言；新增视图只需在分组表加一行，低维护成本。

**Alternatives considered**：把分组直接塞进 `VIEW_META`（污染既有结构、order 难表达）；在组件内硬编码分组（不可测、违反 i18n/数据分离）。

---

## D4. 切换项目时已打开标签页的处置（FR-018）

**Decision**：切换项目 = `projectContext.setProject(id)` 更新全局上下文 → ① **保留**功能视图标签页，按项目取数的视图因订阅了项目上下文 store 而**自动重新取数**；② **自动关闭**「带旧项目参数的上下文详情标签页」，即 `closeMany(t => t.params != null && isContextDetailView(t.view))`，其中 `isContextDetailView` = view ∈ {`instance-log`,`workflow-instance-detail`}（项目维度 id 参数已随项目失效）。激活态回退由既有 `closeMany` 处理（保留 base/pinned，回退末尾或 preferId）。

**Rationale**：函数视图重取靠「视图读上下文 store + store 变更触发重渲染/重查」自然达成，无需逐个手动 reload；详情页参数（如某 instanceId）跨项目无意义，关闭避免展示错数据（满足 SC-007 无串数）。复用既有 `closeMany`，零新增 store API。

**实现要点**：现有按项目取数的视图（catalog/marketplace/datasources 等）当前从 api 函数默认参数取 `projectId=1`；改为这些 api 读 `useProjectContext.getState().currentProjectId`，并让视图的 `useApi`/查询依赖数组纳入 `currentProjectId`，使切换即重查。Pinned 底座视图（freshness/reports/metrics）若按项目取数同样纳入依赖。

**Alternatives considered**：全部关闭重置（打断现场，spec 已否决）；全保留+标失效不关（遗留过期标签需手动清理，spec 已否决）。

---

## D5. 当前功能高亮映射（FR-006/FR-007）

**Decision**：高亮项 = `viewToGroup`/`NAV_ENTRY_VIEWS` 对 `activeTab.view` 的解析。① activeTab.view ∈ `NAV_ENTRY_VIEWS` → 高亮该功能项 + 其所属分组。② activeTab.view ∈ 上下文详情视图 → 经 `detailViewParent` 映射归到父功能/模块高亮（`instance-log` → `ops` 模块；`workflow-instance-detail` → `ops` 模块），不产生错误高亮（FR-007）。③ 无法归属 → 无高亮。高亮订阅 `useWorkspaceStore(s => s.activeTabId)`，标签切换即更新（满足 SC-004 ≤1s，实为同步）。

**Rationale**：纯查表 + store 订阅，O(1)、同步、可单测；详情视图父映射用一张小表显式声明，避免误导高亮。

**Alternatives considered**：用 params 猜父视图（脆弱）；详情视图不高亮任何项（信息量少，方向感弱）。本决策取「归父模块」兼顾准确与方向感。

---

## 附：现状锚点（读码确认）

- `app-shell.tsx`：`<div class="flex h-svh min-w-0"><main…>{children}</main><SidePanel/></div>` —— 左侧导航插入为首个子节点。
- `lib/workspace/store.ts`：`open(view,params,opts)` 去重激活、`activate(id)`、`closeMany(victim,preferId)`、`activeTabId`、base/pinned 语义齐备 —— 全部复用。
- `lib/workspace/views.ts` / `registry.tsx`：18 视图的 title key 与 icon —— 复用。
- `GET /api/projects`：无参 → `findByTenantId`；有参 → `{items,total,page,size}`。本期用无参列表。
- 硬编码 `projectId = 1`/`?? 1`：`catalog-api.ts`（多处）、`datasource-api.ts` —— 改读项目上下文。
- 持久化先例：`lib/date-format-store.ts`（zustand+localStorage 同步初始化）。
