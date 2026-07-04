# Research: 资产目录页面规范化重设计

**Created**: 2026-07-05 | **Plan**: [plan.md](./plan.md)

## D1 — 卡片展开/收起动画方案

**Decision**: 使用 `motion` (Framer Motion) 的 `animate={{ height: "auto" }}` + `exit={{ height: 0 }}` 实现卡片内联详情区的展开/收起动画。

**Rationale**:
- `motion` 已安装在项目中（`frontend/package.json` → `motion: ^12.40.0`），无需新增依赖
- CSS transition 无法在 `height: auto` 场景下工作，需要 magic numbers；`motion` 的 `AnimatePresence` + `height: "auto"` 天然支持
- 项目中其他组件（如 OverlayScrollbars 面板）已使用 motion，有先例

**Alternatives considered**:
- CSS `grid-template-rows` 动画：仅支持固定行高，无法适应内容可变高度
- CSS `max-height` hack：需猜测最大高度值，粗暴不优雅
- Web Animations API：需要手动计算 `scrollHeight`，比 motion 更底层

## D2 — 同时展开卡片数量

**Decision**: 单卡片展开（accordion 模式）。点击新卡片时，当前展开的卡片自动收起。

**Rationale**:
- 卡片详情区信息量大（描述 + 血缘 + 质量 + 操作按钮），同时展开多张会导致页面过长、滚动混乱
- 资产目录场景下用户通常只关注一个资产
- 与 spec 中 US2 的第 5 条验收场景一致

**Alternatives considered**:
- 多卡片同时展开：可并排对比多个资产，但页面高度失控，且与卡片网格的流动性冲突（展开卡片高度不同导致 grid 坑位混乱）

## D3 — 筛选 toolbar 组件方案

**Decision**: 使用项目既有的 `FilterDef` 体系（`segmented` 控件承载敏感度/负责人维度，`search` 控件承载关键词搜索）。

**Rationale**:
- `DataTableToolbar` 已在 `data-table-toolbar.tsx` 中实现了 `segmented`、`multiSelect`、`search`、`toggle` 等完整筛选控件
- 资产目录的筛选维度（敏感度/负责人/标签）直接映射到 `segmented` 和 `multiSelect` 模式
- 复用既有组件可保证视觉和行为与项目其他视图一致（FR-006）

**Alternatives considered**:
- 自建 toolbar：可定制但重复造轮子，违反复用原则
- 保留左侧分面：被用户否决（"还是分栏设计，很不清晰"）

## D4 — 卡片网格响应式列数

**Decision**: 使用 CSS Grid `auto-fill` + `minmax` 实现响应式列数。宽屏 ≥1400px 时 `minmax(380px, 1fr)` 呈现 3 列，中等宽度自动退为 2 列。

**Rationale**:
- 纯 CSS Grid 方案零 JS 开销，浏览器原生响应式
- `minmax(380px, 1fr)` 确保每张卡片最小宽度 380px（刚好容纳完整资产元数据 + Badge）
- 无需 `resize` 监听或 JS 断点

**Alternatives considered**:
- JS `useResizeObserver` + 动态列数 state：过度工程化，380px min-width 的纯 CSS 方案即可满足需求
- Flexbox wrap：无法保证列宽一致，卡片参差不齐

## D5 — i18n 新增 key 清单

**Decision**: 在 `zh-CN.json` 和 `en-US.json` 的 `assetCatalog` 命名空间下新增约 15 个 key，覆盖卡片的展开态文案、筛选状态、空态/错误态。

**Rationale**:
- 当前已存在约 68 个 assetCatalog key（编目/编辑/下线/对账/订阅等写侧已覆盖）
- 缺口在卡片详情展示区——描述标签、血缘标签、质量标签、操作按钮 tooltip、空态卡片、错误卡片
- 新增 key 遵循项目命名规范（camelCase，数据术语英文原样）

**Key 清单**（估算）:
- `cardExpandHint` / `cardCollapseHint`
- `detailDescription` / `detailLineage` / `detailQuality` / `detailMetadata`
- `lineageSource` / `qualityScore` / `qualityNull`
- `filterClear` / `filterActive`
- `errorRetry` / `emptyFiltered`
- `skeletonLoading`

## D6 — 分页方案

**Decision**: 保留 `Pagination` 组件，每页 20 张卡片，页面底部显示分页控件。不做无限滚动。

**Rationale**:
- 资产数量 < 50 条，分页不是性能瓶颈
- `Pagination` 组件已在多处使用，保持一致
- 无限滚动在卡片高度不一时体验差（跳位严重）
