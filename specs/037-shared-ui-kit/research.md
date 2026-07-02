# Phase 0 Research: 复用优先的公共前端组件契约与目录

**Feature**: 037-shared-ui-kit | **Date**: 2026-07-02

本特性无 NEEDS CLARIFICATION（clarify 已消解迁移范围/强制手段/目录形态三项）。Phase 0 的研究任务是**现状盘点 + 落点/收敛决策**，为 Phase 1 与 tasks 提供依据。所有结论基于对 `frontend/` 的只读盘点。

---

## R1 — 现有共享组件家底（决定"编目 vs 新建"）

**Decision**: 9 类原语中 **8 类已有可复用公共组件**，本特性以编目为主、补缺口为辅。

| 原语 | 既有公共组件（真实路径） | 状态 |
|---|---|---|
| 滚动条/滚动区 | `components/ui/dw-scroll.tsx`（OverlayScrollbars，`--os-size:4px`）| ✅ 已有 |
| Tabs | `components/ui/tab-strip.tsx`（卡片式，closable）+ `components/ui/tabs.tsx`（下划线，非 closable，**在 030 分支未合 main**）| ⚠️ 分裂，见 R3 |
| 表格 | `components/ui/data-table.tsx` + `table.tsx`（033 已统一边框包裹，落 main）| ✅ 已有 |
| 下拉框 | `components/ui/select.tsx`（`DropdownSelect`，portal+fixed）| ✅ 已有 |
| 弹框 | `components/ui/dialog.tsx`（@base-ui/react/dialog）| ✅ 已有 |
| 日期选择 | `components/ui/date-picker.tsx` + `calendar.tsx` + `lib/workspace/biz-date.ts`（`yesterdayBizDate`）+ `hooks/use-format-date-time.ts` | ✅ 已有，见 R4 |
| 加载动画 | `components/workspace/shared/loading-state.tsx`（居中转圈 `RefreshIcon`，centered/overlay 双模式，最小 1s 兜底，035 落 main）| ✅ 已有 |
| 刷新入口 | `components/workspace/views/view-refresh-control.tsx`（时间戳+自动/手动开关+旋转）| ✅ 已有 |
| 卡片内边距 | `components/ui/card.tsx` 的 `--card-spacing` token | ✅ 已有，见 R2 |

**Rationale**: 契合宪法 Principle V「复用而非重写」。真正的问题不是缺组件，而是**缺一个让 agent「先查」的索引** + **少数分裂/散落点**。

**Alternatives rejected**: 推倒重建组件库（违反复用原则、巨大回归面）；引入 Storybook 画廊（clarify 已否，交付过重）。

**缺口/散落点（本特性需处置）**：
- 页内**手写下划线 Tab**：`ops-view.tsx`（约 L96–132）、`alerts-view.tsx` 各一份，未复用共享组件 → 差距清单登记 + 示范迁移。
- **魔法间距散落**：`data-table-toolbar.tsx` 等硬编码 `px-2.5/py-0.5/gap-*`，无统一刻度引用。
- **无统一 EmptyState**：各 view 自写 `if (data==null)` 空态（低优先，非 9 类原语，记入清单不强迁）。

---

## R2 — 卡片内边距 token 真相源（FR-010）

**Decision**: 采用**既有 `--card-spacing` 作为卡片内容内边距的单一 token**（默认 `--spacing(6)`=24px、`data-[size=sm]` = `--spacing(4)`=16px），在目录章节文档化为唯一规范；页面级手填内边距（含用户口述的"20/20/20"字面值）一律收敛到此 token，不新造字面数值。

**Rationale**: `card.tsx` 已用 `gap-(--card-spacing)` / `px-(--card-spacing)` / `py-(--card-spacing)` 驱动 Header/Content/Footer——真相源事实上已存在，只是未被文档化、也未被所有页面遵循。用户要的是"单一 token 取代每页微调"，而非某个精确像素值；对齐既有 24/16 刻度即达成目标且零回归。

**Alternatives rejected**:
- 在 `DESIGN.md` frontmatter 新增 `spacing:` 键：`@google/design.md ^0.2.0` 的 schema 仅含 colors/rounded/typography，新增键有 `design:lint` 失败风险；改为在目录章节**散文 + 表格**记录 token 并引用 `globals.css`/Tailwind `--spacing()`，`design:lint` 保持绿。
- 硬造 `--content-padding: 20px`：与既有 24/16 冲突、制造第二真相源。

---

## R3 — Tabs 收敛与 030 跨特性协调（首要风险）

**Decision**: 目录确立**双组件规范**——① closable 的 workspace 主 tab → `TabStrip`（Chrome 卡片式）；② 非 closable 的**页内子 tab** → 下划线式 `Tabs`（030 分支的 `tabs.tsx`）。落地前**必须先协调 030**：优先将 030 的下划线 `tabs.tsx` 合入 037 基线（或在 037 内吸收该实现），随后把 `ops-view`/`alerts-view` 的手写下划线迁移到它。

**Rationale**: 记忆 `frontend-tab-style-rule` 的既定规则是"closable→卡片式、非 closable→下划线式"。当前 main 只有卡片式 `TabStrip`，下划线实现悬在未合的 030 分支——若 037 直接写"下划线统一"却无组件落地，就是宪法所禁的**不闭环**（compiles alone but no-ops once sibling lands）。因此 030 是 037 的硬依赖。

**实测（2026-07-02，已定死处置方式）**：
- `030-unify-tab-styles` 相对 037 基线（merge-base `fb6bf1d`）**只领先 1 个提交 `d094f67`**：新建 `frontend/components/ui/tabs.tsx`（217 行下划线组件）+ 迁移 5 视图（ops/alerts/settings/workflow-canvas/task-editor）+ 030 spec 文档。
- **但** main（037 基线，领先 merge-base 45 提交）已通过另 4 个已落地提交（`4d8440f`/`aa5e4a4`/`699800f`/`25a83de`，均已在 main）用**页内内联下划线**做过"视觉统一"——`ops-view.tsx`(L113)、`alerts-view.tsx`(L138) 至今仍手写 `role="tab"`+`after:bottom-0`，即内联复制而非共享组件。
- 结论：030 的**组件路线**与 main 的**内联路线已分叉**，整体 cherry-pick `d094f67` 会在这 5 个文件冲突。

**处置顺序（写入 tasks 依赖，取代整合并）**：
1. **仅文件级取用组件**：`git checkout 030-unify-tab-styles -- frontend/components/ui/tabs.tsx` 把下划线 `Tabs` 组件单独拿进 037 基线；`pnpm typecheck` 验证其与当前 main 兼容（030 的 5 视图迁移**不取**，因已对 main 陈旧）。
2. 037 基线纳入组件后，目录登记 Tabs 双组件规范（TabStrip 卡片 / tabs.tsx 下划线）。
3. 在**当前 main 状态**上重做 `ops-view`/`alerts-view` 手写下划线 → 共享 `Tabs`（示范迁移，即 T012/T013）。
4. Fallback：若 030 的 `tabs.tsx` 与 main 不兼容/不可用，则在 037 内按 `frontend-tab-style-rule`（closable 驱动双风格）新建等价下划线组件——FR-006 仍全程 in-scope 闭环，不降级为 out-of-scope。

**Alternatives rejected**: 整提交 cherry-pick `d094f67`（与 main 内联路线冲突、回归面大）；037 内另起第三套 Tab 实现（加剧分裂）；忽略 030 只编目卡片式（下划线诉求无处闭环）。

**其它 sibling 着陆状态**（跨特性感知）：033 ✅ main（表格边框）、035 ✅ main（加载转圈）——已在 037 基线，直接作种子条目；034 面包屑已撤回、仅卡片栅格保留，本特性不恢复面包屑，卡片栅格作为"卡片容器"条目一并编目。

---

## R4 — 业务日期默认 + 带时间变体约定（FR-011）

**Decision**: 目录"日期"条目文档化既有约定——**业务日期（bizDate，`yyyy-MM-dd`）为默认主键/主口径**，由 `lib/workspace/biz-date.ts` 的 `yesterdayBizDate()`（T-1 兜底，与后端 `WorkflowTriggerService.defaultBizDate` 同约定）与 `DatePicker` 提供；**需精确到时间**时用统一"带时间变体"——`useFormatDateTime()`（`hooks/use-format-date-time.ts`）+ `date-format-store.ts`（`yyyy-MM-dd HH:mm:ss`，dash/slash 用户偏好），基于 date-fns 单一实现，禁止混用 dayjs/Intl。

**Rationale**: 盘点显示日期口径事实上已统一（纯 date-fns、bizDate 全站 `yyyy-MM-dd`），本特性只需**把约定写进目录**，让下一个 agent 默认走业务日期、需时间时取带时间变体，而非各页自定格式。

**Alternatives rejected**: 新建统一日期组件替换现有（无必要，回归风险）。
**Note**: bizDate 展示常直出后端字符串（已是 `yyyy-MM-dd`），无需过 `formatDateTime`——目录明确"bizDate 直出、时间戳走 `useFormatDateTime`"以免误用。

---

## R5 — 目录落点与形态（FR-001/002，clarify 已定 DESIGN.md）

**Decision**: 在 `frontend/DESIGN.md` 新增顶层章节 **`## 公共组件目录（先查此处 · reuse-first）`**，插入位置在 `## 用法约束`（现 L103–108）之后、既有 per-组件深章节（`## 统一标签条 TabStrip`/`## 滚动条`/`## 数据表格`）之前，作为**索引 + 复用优先约定**，并链接到那些既有深章节，避免重复。

**Rationale**: 目录是"先看这里"的入口，应置于设计原则/约束之后、具体组件规范之前，最贴近 agent 的阅读起点；DESIGN.md 渐进披露、纯文本可 diff，契合宪法 Principle IV 的 agent 知识层。

**Alternatives rejected**: 独立 `CATALOG.md`（clarify 否，真相源分散）；组件画廊（clarify 否）。
**Note（顺带观测，非本特性范围）**：DESIGN.md 现存 `## CopilotKit 主题对齐` 章节疑为 Weft 掉头后遗留（CopilotKit 已随 Weft 删除）——本特性**不改动**它，仅记录以免误导；如需清理另开变更。

---

## R6 — 复用优先的强制手段与验收（FR-002/015，clarify 已定）

**Decision**: 强制手段 = **DESIGN.md 目录（真相源）+ 复用优先约定 + 实现后自查/评审**；自动化 lint/CI 守卫为**可选增强、不在本特性交付**。验收凭 **`specs/037-shared-ui-kit/adoption-inventory.md` 覆盖/差距清单** + 跨页明暗主题浏览器抽查（SC-002/005）。

**Rationale**: clarify 明确以文档+评审为主；覆盖清单把"统一到什么程度"变为可核对清单，避免"看起来统一了"。约定同时写入 DESIGN.md 目录序言与任务创作 Skill 的引用，让 agent 可发现。

**Alternatives rejected**: 硬性 lint 门（clarify 否，规则开发/维护成本，且易误报手写间距）；纯口头约定无清单（不可验收）。

---

## 研究结论汇总（供 Phase 1 / tasks）

1. 以**编目 + 治理文档**为主轴：新增 DESIGN.md 目录章节（R5）+ 复用优先约定（R6）。
2. **收敛间距**：文档化 `--card-spacing` 单一 token，迁移魔法间距（R2）。
3. **Tabs 收敛硬依赖 030**：先协调 030 落地，再编目双组件规范并示范迁移页内下划线（R3）。
4. **日期约定文档化**：业务日期默认 + 带时间变体（R4）。
5. **产出差距清单**：`adoption-inventory.md` 作为验收基线（R6）。
6. 测试门：`design:lint` + `typecheck` + 迁移组件 vitest + 明暗主题浏览器抽查。
