# Phase 0 Research: 血缘探索器入口重构

技术决策与关键取舍。地基 = 052 血缘探索器（已 merge main）；本特性只在读侧投影 + 前端展示层增量。

---

## D1. 字段级连线怎么渲染（表/字段连线，FR-012/013）

**现状**（Explore 实测）：列内联在表节点里（`lineage-node.tsx` 的内联列清单 + `lineage-layout.ts` 按列数增高节点），列级 `DERIVES_FROM` 边被 `lineage-view.tsx#toggleColumns` 用来算每列 `hasLineage` 布尔后**丢弃**；节点只有左右两个节点级 Handle，无列级锚点，故列→列无法连线。

**Decision**：**保留列内联，给每个列行加列级 Handle（source 在右、target 在左，`id = 列 id`），并让 `lineageToFlow` 从展开的列级边产出带 `sourceHandle/targetHandle` 的 ReactFlow 边**，连到具体字段行；表→表边保留、与列级边视觉可区分。

**Rationale**：
- `@xyflow/react` 原生支持「一个节点多 Handle + 边指定 `sourceHandle/targetHandle` 连到具体 handle」，无需新依赖。
- 复用 052 已有的列内联渲染与节点按列增高的尺寸逻辑（`nodeSize` 已含列行高 `COL_ROW_H`），Handle 垂直位对齐列行即可，改动面小。
- 列作为独立画布节点（备选）会让 dagre 把列打散、破坏「列归属于表卡片」的业界通行范式，且要重排布局，成本高、观感差。
- 后端 `expandColumns`（L297）已返回**可闭合**的列节点（含 1 跳邻接列）+ 列级边（from/to = 列 id），数据够用，无需新查询。

**Alternatives considered**：
- 列改独立节点 + dagre 子图 —— 破坏卡片范式、需重排、拒。
- 只在边详情面板看字段映射、图上不连线 —— 用户明确要「连线展示」，拒。

**落点**：`lineage-node.tsx`（列行加 Handle）、`lineage-node-types.ts`（列项带稳定 id）、`lineage-layout.ts`（产出 handle-specific 列级边）、`lineage-graph.ts`（reducer 保留列级边，`columnEdgesByTable` 或并入 edges 带 `granularity==='COLUMN'` 标记，`collapseColumns` 同步移除）、`lineage-view.tsx#toggleColumns`（不再丢边）。

---

## D2. 节点数据源富化（FR-006/007）

**现状**：图查询节点投影（`traverse`/`neighborhood`/`impact`/`pathsBetween` 的 RETURN）**不带 datasource**；但图库里数据已在——写侧 `Neo4jLineageStore.ensureTable` 建了 `:Datasource{id=dsKey, name}` 与 `:Table{datasourceId=dsKey}`，关系 `(:Datasource)-[:HAS_TABLE]->(:Table)`。唯一已带 datasource 的读侧是 `search` 的 Table 分支（`RETURN t.datasourceId AS datasource`）。

**Decision**：在各节点投影 Cypher 追加 `t.datasourceId AS datasourceId`，并 `OPTIONAL MATCH (d:Datasource {dsKey/id: t.datasourceId})` 取 `d.name AS datasourceName`，经 `mapNode` 写入 `GraphNodeView.attrs`（键 `datasourceId`/`datasourceName`）。**走 attrs 而非改 record 结构**，向后兼容、不影响 053 共享 DTO。列节点的数据源随其**所属表继承**（前端按 `parentId` 归属，或投影时一并带出）。

**Rationale**：数据已在图库，纯投影追加；用 attrs 是 052 既定的富属性承载方式（`tableAttrsCypher` 已在 attrs 放 layer/columnCount 等），一致且零破坏。

**Alternatives**：给 `GraphNodeView` 加顶层可空字段 `datasourceId/datasourceName` —— 也可、更强类型，但触碰共享 record、与 053 有并发改同文件风险；**优先 attrs**，若 tasks 阶段判定强类型更稳再评估（记为可选强化）。

**METRIC 节点**（Deferred 定案）：指标无物理数据源 → 不投影 datasource，前端渲染为「无数据源徽标」（或中性「指标」chip）；跨源判定中 metric 端视为「未知来源」，**不因 metric 端把边误判为跨源**。

---

## D3. 跨源边判定与样式（FR-008/013，跨源 vs 库内 vs 未知）

**现状**：无自定义 edgeType，边用 ReactFlow 默认 bezier + 内联 style；样式唯一定义在 `lineage-layout.ts` L157-191，只读 confidence/source/highlight（inferred→虚线、confirmed→绿、highlight→primary）。

**Decision**：**在前端呈现层判定**——`lineageToFlow` 构建节点 map 后，对每条边取两端节点的 `datasourceId`：两端存在且不同 → `cross`；两端相同 → `intra`；任一端未知（含 metric）→ `unknown`。跨源边施加**与 confidence 编码正交**的样式：用**描边色**表达跨源（如 `--color-warning`/琥珀）+ 可选 marker，**保留虚线/绿表达 confidence**（dash=推断、色相=跨源可叠加）。`lineage-legend.tsx` 增图例四项（数据源徽标 / 跨源边 / 库内边 / 未知来源）。列级跨库映射同走此判定。

**Rationale**：跨源是**呈现分类**、非血缘存储语义，放前端零后端改动、不动边 DTO；与 D2 一旦节点带 datasourceId 即可零成本推导。confidence 用 dash、cross 用 color，两维正交不打架。

**Alternatives**：后端在边上打 `crossSource` 标记 —— 冗余（可由节点推导）、改共享 DTO，拒。自定义 edgeType 承载徽标/标签 —— 若需在边上标数据源名可后续升级，MVP 用内联 style 足够。

---

## D4. 搜索优先入口（FR-001~005）

**现状**：`/search`（`q` required）+ `fetchSearch` 齐备；但前端搜索框在 `LineageToolbar`、结果是画布上方的次要下拉，数据源树 `LineageTree` 仍占左栏主入口。

**Decision**：把搜索提为**视觉主入口**——未锚定时画布区呈**搜索 hero**（居中大搜索框 + 默认聚焦）+ 空态文案「搜一个资产开始」；已锚定后搜索常驻可用（保留工具栏搜索或 hero 收起为顶部条）。左栏数据源树**降级**为可选分面浏览（见 D5）。候选项富化展示**所属数据源名**（D2 的 `datasourceName` 或复用 SearchCandidate 富化）以区分同名跨库资产。

**搜索触发方式**（Deferred 定案）：沿用 052 **提交/回车触发**（非输入即查），减少查询压力、与既有一致；候选下拉展示 (数据源·类型·分层) 标注。debounce 自动搜索列为后续可选增强，不入 MVP。

**Rationale**：后端零改动、纯前端把已有能力提到 C 位，直接兑现「不必先选库」。

**落点**：`lineage-view.tsx`（入口编排 + 空态）、新 `lineage-search-hero.tsx`（或改造 toolbar 搜索）、`lineage-api.ts`（SearchCandidate 补 datasourceName）。

---

## D5. 数据源浏览分面（P3 可选，FR-014~016）

**现状**：`LineageTree` 是唯一左栏树，且 datasource→table 级是**占位**（展开 datasource 错调 `fetchColumns`）；后端**无 tables-by-datasource**。

**Decision**（P3，可裁剪）：左栏改为**分面切换器**——`数据源 / 分层 / 最近`（**不含 ownership**）。分层 = 客户端按 `layer` 分组已知资产；最近 = 客户端本地记录会话锚定过的资产；数据源 = 若交付则需新增只读端点 `GET /api/lineage/datasources/{id}/tables`（Cypher `MATCH (d:Datasource{id})-[:HAS_TABLE]->(t:Table)`）修正占位跳级。**整体可从缺**——裁剪时 US1 搜索仍是完整主入口。

**Rationale**：用户明确分面浏览「可有可无」；P1（搜索 + 跨库可辨）不依赖它。故按 P3 独立切块，容量不足则只交付端点占位修正或整体延后。

**Alternatives**：主题域/标签分面 —— 血缘资产无对应元数据，本期从缺（spec 已假设消解）。

---

## D6. 测试策略

- **后端真 Neo4j IT**（`LineageDatasourceProjectionIT`）：seed 跨数据源链（`mysql.user → hive.dwd_user → hive.dws_user → pg.rpt_user`）+ 列级 `DERIVES_FROM`；断言 ① neighborhood/upstream/downstream 节点 attrs 带 `datasourceId`+`datasourceName`；② 列节点可归属数据源；③ `search` 候选带 `datasourceName`，同名跨库可区分；④（若交付）`/datasources/{id}/tables` 返回该库的表；⑤ 跨项目隔离——他项目资产不泄漏。
- **h2 REST shape 契约**（`LineageGraphEndpointTest`）：新字段/新端点包络 `{code,data}` 形状 + 隔离回归 + 既有端点向后兼容（旧字段不丢）。
- **前端 vitest**：`lineage-layout` 单测——跨源分类三态（intra/cross/unknown）正确；列级边产出带正确 `sourceHandle/targetHandle`；配色工具确定性 + 耗尽兜底。
- **Playwright 浏览器门**（沿用 052 登录绕过 + SSE 直连约定）：① 首屏搜索聚焦、空态文案、不碰树即可锚定；② 横跨多库图节点显数据源徽标；③ 跨源边样式区别于库内边、图例在位；④ 展开列后字段级连线连到具体列行。
- **回归**：052 双向/深度/展开/影响/路径/列级行为不回退（SC-006）。

---

## Deferred 定案汇总（clarify 阶段挂起项，本 research 关闭）

| Deferred 项 | 定案 |
|---|---|
| METRIC 节点数据源归属 | 指标无物理数据源 → 无徽标 / 中性「指标」chip；跨源判定视 metric 端为「未知来源」，不误判跨源（D2/D3） |
| 搜索触发方式（即时 vs 提交） | 沿用 052 提交/回车触发；debounce 自动搜索为后续可选增强，不入 MVP（D4） |
