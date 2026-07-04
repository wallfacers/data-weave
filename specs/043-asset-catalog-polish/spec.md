# Feature Specification: 资产目录页面规范化重设计

**Feature Branch**: `043-asset-catalog-polish`

**Created**: 2026-07-05

**Status**: Draft

**Input**: User description: "基于当前项目的前端规范，优化资产目录页面，这个页面的很多地方没有国际化，给我的感觉就是一个demo，操作和布局也是有问题的，大胆设计但是组件相关的必须符合项目规范，重新给我设计"

## Clarifications

### Session 2026-07-05

- Q: 资产目录页整体布局（三栏 vs 两栏 vs 卡片网格 vs 看板）？ → A: 卡片网格 + 行内展开——筛选 toolbar 在顶部，资产以卡片网格呈现，点击卡片原地展开详情，操作按钮平铺可见

## 背景与问题

当前资产目录页面（023 后端基础 + 029 写侧闭环）功能完整，但视觉与交互呈现明显原型感：

- **布局拥挤**：三栏（分面 | 列表 | 详情）并排，每栏可用空间不足，分面区域被压缩、列表行高密、详情面板信息堆叠无层次
- **组件不统一**：列表使用原生 `<button>` 承载资产条目，与项目既有的 `DataTable` 组件体系脱节；状态提示使用硬编码琥珀色内联样式，而非项目统一的 `Badge` 语义变体
- **交互粗糙**：Toast 使用自建 `setTimeout` 方案而非项目全局 `sonner`；加载态仅有文字"加载中"；空态仅有文字"暂无资产"；错误态无统一样式
- **视觉细节缺失**：资产条目无 hover 过渡动画、选中态仅为背景色加深无侧边指示、详情面板各区块平铺无分隔无分组
- **国际化不全**：部分提示文案（如对账结果、状态描述）硬编码在组件中

本特性在保持既有功能完备性的前提下，对资产目录页面进行**规范级重设计**：布局从三栏改为**顶部 filter toolbar + 卡片网格**，资产以独立卡片呈现（非表格行），点击卡片原地展开详情，操作平铺可见，交互对齐项目规范，补全 i18n。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 资产卡片网格浏览与筛选 (Priority: P1)

数据治理人员在资产目录中以卡片网格浏览资产、通过顶部 toolbar 筛选快速缩小范围、感知当前筛选状态并一键清除。

**Why this priority**: 浏览与筛选是资产目录最高频的交互；卡片网格比表格行提供更丰富的视觉信息密度，清晰的信息架构和即时反馈直接影响用户对整个产品品质的感知。

**Independent Test**: 打开资产目录页——顶部 toolbar 展示分段筛选控件（敏感度/负责人）+ 搜索框 + "编目资产"按钮，下方资产以卡片网格呈现（每卡含名称、限定名、敏感度 Badge、状态 Badge、负责人、更新日期），筛选切换后卡片以 loading 骨架过渡。

**Acceptance Scenarios**:

1. **Given** 用户打开资产目录，**When** 页面加载完成，**Then** 资产以响应式卡片网格呈现（宽屏三列，中等两列，窄屏单列），每张卡片含资产图标、名称（加粗）、限定名（muted）、敏感度 Badge、状态 Badge、负责人、更新日期
2. **Given** 用户在顶部 toolbar 点击 segmented 控件筛选敏感度，**When** 选中 "CONFIDENTIAL"，**Then** 该段高亮为选中态，非匹配资产卡片以动画淡出，匹配卡片保留；toolbar 中出现"清除筛选"按钮
3. **Given** 筛选条件已激活，**When** 用户点击"清除筛选"，**Then** 所有筛选条件重置为"全部"，卡片网格恢复完整展示
4. **Given** 搜索结果为空，**When** 卡片区域渲染，**Then** 显示统一的空态卡片（图标 + 文案 + "清除筛选"快捷操作按钮）
5. **Given** 网络异常或接口返回错误，**When** 卡片区域渲染，**Then** 显示统一的错误态卡片 + "重试"按钮

---

### User Story 2 - 资产详情内联展开 (Priority: P1)

用户点击资产卡片后，卡片原地向下展开详情区域，展示资产的描述、血缘、质量评分与可用操作，无需跳转或弹出新窗口。

**Why this priority**: 详情是资产治理操作的入口，信息层次决定操作效率。卡片内联展开避免了侧面板或 Dialog 的上下文丢失问题。

**Independent Test**: 点击一张资产卡片——卡片 border 高亮、原地向下展开详情区（描述/血缘/质量/元数据标签/操作按钮），再次点击或点击卡片外区域收起；无血缘/质量数据时对应行隐藏而非显示错误。

**Acceptance Scenarios**:

1. **Given** 用户点击一张 ACTIVE 状态资产卡片，**When** 卡片展开，**Then** 详情区展示：描述文本、血缘摘要（单行）、质量评分 Badge（含分数）、元数据标签行（负责人/管家/tag）、操作按钮组（✎ 编辑/⬇ 下线/🔄 对账/🔔 订阅/退订）平铺可见
2. **Given** 选中资产缺少血缘数据，**When** 卡片展开，**Then** 血缘行完全隐藏（非显示错误或空值），不影响其他信息
3. **Given** 选中资产状态为 STALE，**When** 卡片展开，**Then** 详情区顶部显示 warning 色调提示条"底层表已变更，待对账"，并提供"对账"操作入口
4. **Given** 当前用户已订阅该资产，**When** 卡片展开，**Then** 操作区显示"已订阅"状态徽章 + "退订"按钮；**When** 未订阅，**Then** 显示"订阅"按钮
5. **Given** 用户再次点击已展开的卡片（或点击另一张卡片），**When** 交互发生，**Then** 当前展开卡片平滑收起，新卡片（如有）展开；同时只展开一张卡片

---

### User Story 3 - 资产编目与编辑 (Priority: P2)

数据管理员通过统一的表单 Dialog 创建新资产或编辑现有资产元数据，表单字段对齐项目表单组件规范，校验即时反馈。

**Why this priority**: 编目与编辑是资产治理的写入口；表单体验直接影响元数据质量。

**Independent Test**: 点击"编目资产"→弹出 Dialog→填写字段→提交→列表刷新；选中资产→点击编辑→弹出预填 Dialog→修改字段→提交→详情刷新。

**Acceptance Scenarios**:

1. **Given** 用户点击"编目资产"，**When** Dialog 打开，**Then** 数据源 Select 已预加载选项；限定名输入框有必填提示；提交按钮在表单填写过程中可用
2. **Given** 用户在 Dialog 中提交了重复限定名，**When** 后端返回 `catalog.duplicate_asset`，**Then** 表单在限定名字段旁显示明确错误提示（红色），不关闭 Dialog
3. **Given** 用户打开编辑 Dialog，**When** 仅修改描述字段后提交，**Then** 使用 PATCH-diff 仅提交变更字段，未触及字段保持原值
4. **Given** 编辑操作提交中，**When** 用户点击提交按钮，**Then** 按钮显示 loading spinner 并禁用重复点击

---

### User Story 4 - 订阅管理 (Priority: P3)

用户通过"我的订阅"聚合面板查看所有订阅的资产，支持批量退订或从详情面板单独退订。

**Why this priority**: 订阅管理是资产治理的收尾环节，使用频次低于浏览和编辑。

**Independent Test**: 点击"我的订阅"→弹出 Dialog→列表展示已订阅资产→点击退订→确认→列表刷新。

**Acceptance Scenarios**:

1. **Given** 用户有已订阅资产，**When** 点击列表头"我的订阅"按钮，**Then** Dialog 以列表展示所有订阅（含资产名、订阅时间），每个条目有退订按钮
2. **Given** 用户无任何订阅，**When** 点击"我的订阅"按钮，**Then** Dialog 显示空态（图标 + "暂无订阅"文案）

---

### Edge Cases

- 数据源列表加载失败时，编目 Dialog 的数据源 Select 显示空态提示而非白屏
- 用户快速双击卡片时，展开/收起动画防抖，不产生闪烁
- 卡片网格在筛选条件变化时，已展开的卡片自动收起
- 卡片展开后若该资产从筛选结果中消失（如其他操作改变状态），卡片自动收起
- 暗色/亮色主题下所有徽章、状态色、选中态均保持可分辨
- 长资产名（超过 40 字符）在卡片和展开详情中正确截断（省略号），hover 显示完整名称
- 资产数量较少（≤ 3 项）时卡片不占满整行，保持自然宽度（不对齐拉伸）
- 骨架卡片数量与实际卡片数量一致——初次加载时根据前次 total 或默认 pageSize 渲染占位数

## Requirements *(mandatory)*

### Functional Requirements

**布局与结构**：

- **FR-001**: 系统 MUST 将页面改为**顶部 filter toolbar + 卡片网格**布局——筛选条件以 segmented/multiSelect 控件置于顶部 toolbar（对齐项目 `DataTableToolbar` 模式），资产以响应式卡片网格（默认 2-3 列）呈现
- **FR-002**: 卡片网格 MUST 支持响应式列数：宽屏 ≥1400px 时三列，中等宽度 900-1399px 时两列，窄屏 <900px 时单列

**资产卡片规范**：

- **FR-003**: 每张资产卡片 MUST 展示：资产图标（Database01Icon）、名称（主文本，加粗）、限定名（次级文本，muted）、敏感度 Badge（项目 Badge 组件语义变体）、状态 Badge、负责人、最近更新日期
- **FR-004**: 卡片 MUST 具备 hover 态（border 色变为 primary 或蓝色）和展开态（border 高亮 + 背景微升 + 内联详情区滑出），过渡动画 `transition-colors duration-200`
- **FR-005**: 卡片详情区（展开态）MUST 内联展示：描述文本、血缘摘要（单行，可截断）、质量评分徽章、元数据标签行（负责人/管家/tag），以及操作按钮组（编辑/下线/对账/订阅）平铺可见

**筛选交互规范化**：

- **FR-006**: 筛选条件 MUST 置于顶部 toolbar，使用 `segmented` 分段控件承载敏感度/负责人/标签维度（对齐项目 `FilterDef` 体系），使用 `search` 控件承载关键词搜索
- **FR-007**: 已激活的筛选项 MUST 有明确视觉指示——segmented 控件中选中段为 `bg-accent` 背景加深，tag 类 chip 选中为蓝色边框 + 淡蓝底色；任一筛选激活时 toolbar 中显示"清除筛选"按钮
- **FR-008**: 筛选条件变化时，卡片网格 MUST 以统一 loading spinner 过渡（使用 `LoadingState` 组件），无布局跳动；结果为空时显示空态卡片（图标 + 文案 + "清除筛选"快捷操作）

**详情展示（卡片内联展开）**：

- **FR-009**: 点击卡片时，详情 MUST 在卡片内部原地展开（非弹出 Dialog 或侧边 Sheet），将卡片向下扩展出详情区，包含：描述文本、血缘摘要（单行可截断）、质量评分徽章（含评分数字）、元数据标签行（负责人/管家/tag）
- **FR-010**: 资产状态 MUST 使用项目 `Badge` 组件展示（ACTIVE→success / STALE→warning / RETIRED→muted），禁止硬编码颜色
- **FR-011**: 敏感度 MUST 使用项目 `Badge` 组件展示（INTERNAL→默认 / CONFIDENTIAL→warning / PII→destructive / PUBLIC→secondary）
- **FR-012**: 血缘与质量数据缺失时 MUST 隐藏对应行（而非显示错误占位），仅在数据存在时渲染；STALE 状态资产在展开详情中显示 warning 色调提示"底层表已变更，待对账"

**交互反馈规范化**：

- **FR-013**: 所有 toast 提示 MUST 使用项目全局统一的 `sonner` toast 系统，移除当前自建的 `setTimeout` 方案
- **FR-014**: 卡片网格加载态 MUST 使用 `LoadingState` 组件，替换文字"加载中"；初次加载渲染骨架卡片（3-6 张灰色占位卡片）
- **FR-015**: 搜索结果为空或筛选后无匹配时 MUST 显示统一的空态卡片（图标 + 文案 + "清除筛选"快捷操作按钮）
- **FR-016**: 网络异常或接口返回错误时 MUST 显示错误提示卡片 + "重试"按钮
- **FR-017**: 卡片展开/收起 MUST 使用 `motion` (Framer Motion) 或 CSS transition 实现平滑的高度动画，无突兀跳变

**国际化**：

- **FR-018**: 所有用户可见文本 MUST 经 `next-intl` `useTranslations` 获取，零硬编码中英文用户可见字符串
- **FR-019**: `zh-CN.json` 与 `en-US.json` 两 bundle 中 `assetCatalog` 命名空间 MUST 保持 key 集严格一致
- **FR-020**: 所有新增文案 key MUST 遵循项目命名规范（camelCase，数据术语英文原样保留）

**操作安全性**：

- **FR-021**: 所有写操作（创建/编辑/下线/对账/退订）MUST 经现有 `GateActionService` 闸门，`resolveGate` 三态分流（EXECUTED→成功 toast / PENDING_APPROVAL→"已提交审批" toast / REJECTED→后端错误消息 toast），零旁路
- **FR-022**: Dialog 提交按钮在请求飞行中 MUST 显示 loading spinner 并禁用，防止重复提交

### Key Entities

- **Asset（资产）**：数据资产的元数据记录。关键属性：id、qualifiedName（限定名）、name（显示名）、description、sensitivity（敏感度级别）、status（ACTIVE/STALE/RETIRED）、ownerId、stewardId、tags、lineageTableRef。关联数据源、血缘、质量徽章。
- **AssetSubscription（资产订阅）**：用户对资产的变更订阅记录。关键属性：subscriptionId、assetId、subscribedAt。
- **AssetFacets（分面计数）**：服务端返回的聚合计数。关键维度：sensitivity、owner、tag、status（只读）、certification。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 资产目录页所有用户可见文本 100% 来自 i18n bundle，零硬编码中英文字符串（代码注释和 console 日志除外）
- **SC-002**: 列表筛选从点击分面项到展示新结果的反馈延迟不超过 500ms 感知等待（loading spinner 即时出现）
- **SC-003**: 暗色/亮色双主题下，所有徽章、状态指示、分面选中态颜色均可分辨，无对比度不足导致的"看不清"问题
- **SC-004**: 编目/编辑表单提交成功率不低于现有基线（零功能回退、零新增业务错误码场景）
- **SC-005**: 资产目录页的卡片网格、toolbar、Badge、间距、字体层级与项目其他视图（数据新鲜度、运维中心）风格一致——使用相同的间距 token、字体层级、组件变体，用户可在视图间无缝切换
- **SC-006**: 用户在 3 秒内可完成从打开资产目录到通过筛选找到目标资产卡片并展开查看详情的全流程（top toolbar 筛选 + 卡片点击展开）

## Assumptions

- 后端 API 契约不变——所有 17 个 `/api/catalog/*` 端点行为维持现状
- 项目既有的 `DataTable`、`Badge`、`Button`、`Dialog`、`Pagination`、`Select`、`Input`、`LoadingState`、`sonner` 组件可用于本特性改造
- 暗色/亮色主题的 CSS 变量已由 `globals.css` 与 `DESIGN.md` 定义，本特性不引入新颜色 token
- 资产种子数据量较小（< 50 条），分页可用性优先于虚拟滚动等大数据优化
- 指标市场视图已在 042 中移除，本特性仅涉及资产目录视图（`asset-catalog-view.tsx`）
- 零后端 / schema 改动——与 029 约束一致
