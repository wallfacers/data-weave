# Phase 0 Research: Dashboard-01 外壳布局迁移（保留多 Tabs）

本特性无技术未知量（纯前端、既有栈、既有组件与数据源）。研究聚焦于**面包屑落在哪一层、数据从哪来、如何不违背「无分割线」条款**，以及"卡片/表格是否需要新组件"的范围判断。

## Decision 1：面包屑挂载点 —— `workspace.tsx` 单点注入（而非逐视图组件内嵌）

**Decision**：在 `components/workspace/workspace.tsx` 的 `<WorkspaceTabBar />` 之后、`tabs.filter(...).map(...)` 内容渲染区之前，插入新增组件 `<WorkspaceBreadcrumb />`。该组件读取 `activeTabId` 对应的 `WorkspaceTab`，纯函数式派生出面包屑节点序列并渲染，不接受任何 props 之外的外部状态。

**Rationale**：
- 面包屑内容完全由「当前激活 Tab 的 `view`/`params`」决定，是无状态派生数据，不需要每个视图各自维护——单点渲染即可覆盖全部 18 个视图，天然满足 FR-001/002（多 Tabs 独立且实时更新：`activeTabId` 切换即触发重渲染，keep-alive 的隐藏 Tab 不渲染面包屑、激活时才算，无残留问题）。
- 避免向 ~18 个视图文件注入新 props/新导入，改动面从"N 个视图"降为"1 个新组件 + workspace.tsx 一行插入"，与 CLAUDE.md「不做超出任务范围的重构」原则一致。
- 不需要感知侧边栏折叠状态（`useNavUiStore`）——面包屑与侧边栏折叠态解耦，天然满足 US1 Acceptance Scenario 2（折叠后面包屑不受影响）。

**Alternatives considered**：
- **A. 每个视图组件内部渲染自己的面包屑**：需要改 18 个文件、易漏、面包屑文案与 Tab 元数据脱节风险高（每个视图手写标题字符串而非复用 `VIEW_META`）。❌
- **B. 面包屑塞进 `tab-bar.tsx` 的 `TabStrip` 内部**：`TabStrip` 是通用组件（工作区主 tab 条/日志面板/侧面板 mini tab 三处复用，见 `DESIGN.md`「统一标签条」段），塞入面包屑语义会污染其通用性。❌

## Decision 2：面包屑数据源 —— 全部复用 `032` 已交付的既有数据，零新建配置表

**Decision**：面包屑节点序列 = `[项目名, 分组标题, 视图标题, (可选)动态参数值]`：
- 项目名：`useProjectContext` 的 `current?.name`（与 `left-nav.tsx` 的 `ProjectSwitcher` 同源）。
- 分组标题：`resolveActiveHighlight(view).group` → 在 `NAV_GROUPS` 中查对应 `titleKey`（`leftNav.groups.<id>` 命名空间，**已双语存在，不需新增 i18n key**）。
- 视图标题：`VIEW_META[view].title`（`views.<viewType>` 命名空间，**已双语存在**）。
- 动态参数值：复用 `tab-bar.tsx` 现有的 `tabLabel()` 逻辑（"标题 · 首个 param 值"约定），把该函数从模块私有改为 `export`，面包屑与 Tab 标签共用同一套"参数如何显示"的约定，避免两处实现漂移。

**Rationale**：
- `nav-groups.ts` 的 `resolveActiveHighlight` 已经处理了入口视图与上下文详情视图（`instance-log`/`workflow-instance-detail`）两种归属逻辑（FR-007 的详情视图归父模块），面包屑直接复用即为「层级路径」的现成答案，不需要重新设计一套"面包屑专用"分组表。
- 零新增 i18n key（除面包屑自身的无障碍 `aria-label`/兜底文案外）——分组/视图标题双语已存在，直接省掉一次 `messages/{zh-CN,en-US}.json` 同步风险。
- `tabLabel()` 抽出复用而非面包屑另写一套动态参数格式化逻辑，保证"Tab 标签"与"面包屑末级"对同一 Tab 展示一致的动态文案，减少认知负担。

**Alternatives considered**：
- **新建 `breadcrumb-config.ts` 独立配置表**：与 `nav-groups.ts` 语义重复（同样是 view→group 的映射），维护两份易漂移。❌
- **面包屑末级动态参数另起一套格式化函数**：与 Tab 标签逻辑不一致，用户会看到 Tab 写"任务A · 123"、面包屑写"任务A（ID: 123）"两种风格。❌

## Decision 3：无分割线条款下的面包屑/Tab 条视觉区隔 —— 背景层次 + padding，不加边框线

**Decision**：面包屑行与上方 `WorkspaceTabBar`、下方内容区之间**不加 `border-b`/`border-t`/`<Separator>`**；面包屑行使用比内容区略浅一档的背景（复用 Tab 条既有的 `bg-foreground/[0.04]` 淡叠或同色系但独立一档），靠 `px-4 py-2` 量级的留白与背景明度差完成区隔，与 `DESIGN.md`「布局：无分割线（项目偏好）」条款保持一致。

**Rationale**：`DESIGN.md` 明文规定 header/content 之间禁止分割线（❌ `border-b`/`border-t`/横向 `<Separator>`/`<hr>`），这是项目级红线而非本特性可自行斟酌的细节；面包屑行本质是内容区的 header 分区，必须遵守同一条款，不能因为它是"新加的一行"就破例加线。

**Alternatives considered**：
- **面包屑行加 `border-b` 与内容区分隔**：直接违反 `DESIGN.md` 明文红线。❌
- **面包屑与 Tab 条合并成一行**（挤在 `TabStrip` 右侧）：违反 Decision 1 的组件隔离考量，且窄视口下拥挤。❌

## Decision 4：卡片/表格栅格 —— 不新建组件，以 `metrics-view.tsx` + 033 的 `DataTable` frame 为既定标杆做审计对齐

**Decision**：不为"dashboard-01 卡片网格"新建共享组件；`metrics-view.tsx` 的 `grid gap-3 sm:grid-cols-2 lg:grid-cols-4` + 图标/数值/标签卡（`MetricCard`）已经是该模式的可复用范式，其余含统计卡片的视图（`fleet-view.tsx`、`reports-view.tsx` 等）在实现阶段逐一审计是否已符合该栅格惯例，偏离的做最小 class 调整对齐，不做组件级重构。数据表格类视图的版式基线以并行分支 `033-ui-table-frame-spacing` 合入后的 `DataTable` 边框 frame 为准（该分支已把"三段式固定布局 + 统一边框"下沉进组件，本特性零需重复实现，仅确认各调用点合入后表现符合预期）。

**Rationale**：
- 造轮子成本 > 收益——项目已经天然长出了两个可复用的"长相标杆"（统计卡片网格、DataTable frame），dashboard-01 要的"卡片化 + 表格化仪表盘观感"本质已经具备，缺口只是面包屑（Decision 1-3）。
- 与用户澄清结论一致（"把现有的页面，布局，表格弄好即可，图不要"）——即"整理对齐"而非"重建"。
- 复用 033 的产出避免两个并行分支各自定义一套表格版式标准，产生合并冲突或视觉不一致。

**Alternatives considered**：
- **新建 `DashboardCard`/`DashboardGrid` 抽象组件**：当前只有一种卡片网格用法（`sm:grid-cols-2 lg:grid-cols-4` 的统计卡），提前抽象是无收益的过度设计。❌
- **本特性重新定义 DataTable 版式**：与 033 重复劳动且可能冲突，应审计+对齐而非重做。❌

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| 面包屑分组标题依赖 `resolveActiveHighlight`，若某视图既非入口视图也非已注册详情视图（未来新增视图遗漏归类）会返回空分组 | FR-003 兜底：分组为空时面包屑跳过该级，只显示「项目 > 视图名」，不报错、不留白白级 |
| 与并行分支 `033-ui-table-frame-spacing` 在 `settings-view.tsx`/`data-table.tsx` 上的审计基线可能因合并顺序不同而失真 | 实现阶段以两分支中**更晚合入 main** 的版本为准复核一次（CLAUDE.md 跨 feature 对齐条款） |
| 窄视口下面包屑文案过长换行，撑高 header 行 | Decision 3 的 padding 区隔需配合 `truncate`/响应式隐藏中间级（仅保留首尾）兜底，实现阶段用浏览器门验证窄视口 |
| Turbopack 全局 CSS 陈旧缓存致新样式不显 | 参照记忆 [[turbopack-global-css-stale-hmr]]：改后清 `.next` 重启再验 |
