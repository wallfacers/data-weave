# Phase 0 Research: Lineage Graph Explorer

技术决策汇总（Decision / Rationale / Alternatives）。基线：`@xyflow/react ^12.11` 已在仓库并被工作流 DAG 使用；Neo4j 是血缘唯一存储；本特性全部查询只读。

## 前端

### D1. 画布复用 —— `DagRenderer` + 抽出 `FlowCanvasWithPanel`

- **Decision**: 血缘画布直接复用纯 ReactFlow 画布组件 `DagRenderer`（`components/workspace/dag-renderer.tsx`，内置 fitView / Background 网格 / Controls 缩放平移 / 可选 MiniMap / readOnly 保留 click/context 交互），传入血缘自定义 `nodeTypes`。把 `DagDialogInner` 里「画布 + 可拖拽宽度嵌入面板 + Escape 关面板」那段（`dag-dialog.tsx:203-259`）抽成**与 Dialog 无关的 `FlowCanvasWithPanel`** 壳，工作流 Dialog 与血缘 view 共用。
- **Rationale**: `DagRenderer` 已被 `workflow-canvas-view.tsx:864` 以非 Dialog 方式直接使用，证明可脱离弹窗；它对领域零耦合，只吃 `Node[]/Edge[]/NodeTypes`。可调宽嵌入面板是最有价值但被锁在 Dialog 内的资产，抽出即满足「详情嵌入画布、可关闭」的澄清决策，且工作流侧同步受益（reuse-first）。
- **Alternatives**: ① 直接用 `DagDialog` —— 否决：它是 Dialog 且 `nodeTypes` 写死 `{task,virtual}`，血缘要的是三栏 workspace view。② 血缘 view 自建三栏、面板宽度逻辑各写一份 —— 否决：重复 DagDialog 已有逻辑，违背 reuse-first。
- **注意**: `DagRenderer` 不自带 `ReactFlowProvider`，非 Dialog 使用时血缘 view/`FlowCanvasWithPanel` 需自包一层。

### D2. 分层布局 —— 引入 `@dagrejs/dagre`（LR）

- **Decision**: 新增依赖 `@dagrejs/dagre`，写纯函数 `lineageToFlow(graph)`（`lib/workspace/lineage-layout.ts`）做 rankdir=LR（上游左→下游右）分层布局，产出 `{nodes,edges}` 交给 `DagRenderer`。
- **Rationale**: 工作流 DAG **无自动布局**（坐标来自后端 `posX/posY`，用户拖拽回存），血缘没有人工坐标、必须自动分层。dagre 是 ReactFlow 生态标准分层布局，成熟、体积小、专治「rank 分层 + 同层排序减少交叉」——手写交叉最小化成本高。血缘子图有 `truncated` 上限（≤2000），dagre 性能足够。它是布局工具而非 UI 原语，不与 reuse-first（约束 UI 原语）冲突。
- **Alternatives**: ① 自研最长路径分 rank —— 可行且免依赖，但同 rank 内排序减少边交叉是难点，效果不如 dagre；作为 fallback 记录。② `elkjs` —— 更强但体积大、异步 API 更重，血缘规模用不上，否决。

### D3. 血缘节点组件 —— 新建 `lineage-node.tsx` + `LineageNodeData`

- **Decision**: 新建血缘节点组件与数据类型，注册为独立 `lineageNodeTypes`（`{datasource, table, column, metric}`），Handle 左=target / 右=source 契合上游左→下游右。表节点支持「展开列」内联列清单（US4）。
- **Rationale**: 血缘节点语义（层色 ODS/DWD/DWS/ADS、新鲜度、synced rows、可展开列、置信度）与工作流 `CanvasNodeData`（run state）完全不同，不应复用同一 node type。注册接入点与工作流一致（`nodeTypes` prop）。
- **Alternatives**: 复用 `TaskNode` —— 否决，语义错配。

### D4. 详情面板 —— 复用 `DetailPanelShell` + headerExtra Tabs + 新选中态 store

- **Decision**: 用通用 `DetailPanelShell`（领域无关：title/onClose/loading/error/hasData/children/headerExtra）承载血缘面板，`headerExtra` 放「节点 / 边 / 影响」Tab。现有 `edge-detail-panel.tsx`（人工裁决 confirm/remove/revoke）与 `impact-panel.tsx` 的**业务逻辑保留**，仅把自绘外壳换成 `DetailPanelShell`。新建 `lineage-selection-store.ts`（不复用 `useNodeDetailStore`）。
- **Rationale**: `DetailPanelShell` 已被 DAG 查看器与实例面板共用，`headerExtra` 明确支持 Tab 条。`useNodeDetailStore.selectNode` 硬编码请求 `/api/ops/workflows/{id}/nodes/...`，与血缘无关，不可复用。
- **Alternatives**: 保留 `edge-detail-panel` 自绘外壳 —— 否决，违反 reuse-first（面板骨架应统一）。

### D5. 工具栏原语 —— 新建共享 `Segmented` + `Stepper`（回填 DESIGN.md）

- **Decision**: 新建两个共享 UI 原语：`components/ui/segmented.tsx`（方向 上/下/双向、粒度 表/列）与 `components/ui/stepper.tsx`（深度 −N+），**同一改动内回填 DESIGN.md「## 公共组件目录」原语速查表 + 深章节**。搜索用现有 `Input`；下拉用 `DropdownSelect`；滚动 `DwScroll`；加载 `LoadingState`；刷新 `ViewRefreshControl`；树复用 `LineageTree`（修其列加载占位逻辑为真三级下钻）。
- **Rationale**: 全仓无独立 Segmented（仅私有于 `data-table-toolbar`）与无任何 stepper/number-input 组件；DESIGN.md 硬规则要求「目录确无该能力才新建，且同一改动内回填目录」。现有血缘 `lineage-flow.tsx` 手写按钮 toggle 违反 reuse-first，必须替换为规范原语。
- **Alternatives**: 用下划线 `Tabs` 表达方向/粒度 —— 可降级但语义上 segmented toggle 更贴切；深度无任何可替代，必须新建 stepper。

### D6. 数据层与注册 —— 保留 `lineage-api.ts` 与视图键

- **Decision**: 视图注册（`registry.tsx` `lineage`、`views.ts` `views.lineage`）保留；`lineage-api.ts` 保留并**启用已存在但未被调用的 `fetchUpstream`/`fetchColumnUpstream`** 做方向切换，新增 `search`/`paths` 客户端与富属性/`reachableTotal` 类型；`lineage-flow.tsx` 废弃。i18n 新键落 `lineageView` 命名空间（zh-CN/en-US 双写），刷新文案复用 `viewRefresh`。
- **Rationale**: 双向所需上游端点客户端已就绪（后端也已返回边，见 D8）；改动最小化。

## 后端

### D7. 按名搜索 —— MVP 走 `toLower CONTAINS`，fulltext 为 follow-up

- **Decision**: 新增 `LineageQueryService.search(...)` + `GET /api/lineage/search` + DTO `SearchCandidate(id,type,name,layer,datasource)`。MVP 用多标签 UNION 的 `toLower(x) CONTAINS toLower($kw)`（Table 匹配 `qualifiedName`、Column 匹配 `name`、Metric 匹配 `name`），`YIELD` 后强制 `WHERE tenantId/projectId` 隔离，复用 `clampLimit`（默认 100 / 硬顶 2000）+ offset，空结果返回 `[]`。**不新增 Neo4j 索引**。fulltext index（`CREATE FULLTEXT INDEX asset_name_ft ...`）作为规模增长后的优化项记录，届时加到 `Neo4jSchemaInitializer`。
- **Rationale**: 数据规模在 ≤2000 量级，CONTAINS 单查询满足中缀匹配（`order_detail` 片段）且零 schema 改动、隔离不变量可控；fulltext 虽更快但不能直接分区（须 YIELD 后二次过滤）、语法差异需改初始化器，MVP 不值当。
- **Alternatives**: `STARTS WITH` 前缀 —— 否决（用户输中缀）；一开始就 fulltext —— 推迟。
- **门禁**: 只读，不经 PolicyEngine。

### D8. 影响分析返回边 + 真实可达总数

- **Decision**: `impact()` 把 `edges=List.of()` 替换为真实边投影（照抄 `traverse()` 已验证的 `edgeCypher` 模式：`UNWIND relationships(path) AS r ... WITH DISTINCT r`），并经 `annotateCorrections` 保证 REMOVED 边不出图（FR-018）；**边集必须闭合于返回节点集**（应用层用 downstream id set 过滤悬挂边）。另加独立 COUNT 查询（`WITH DISTINCT end LIMIT $countCap RETURN count(end)`）产出 `reachableTotal`，达 cap 时 `totalIsLowerBound=true`。`ImpactResult` 新增 `reachableTotal`+`totalIsLowerBound`，`nodeCount` 保留为当前页条数。
- **Rationale**: `traverse` 已证明边投影可行；`nodeCount` 只反映当页不满足 FR-013「真实可达总数」。COUNT 与分页解耦 + cap 防深图指数膨胀（FR-013 下限表达）。
- **Alternatives**: 无 cap 纯 count —— 深图超时风险，否决。

### D9. 双向子图带边 —— 服务层下沉，无向遍历

- **Decision**: `LineageQueryService` 新增 `neighborhood(tenant,project,id,depth,granularity)`，一条 Cypher 用**无向** `-[:FLOWS_TO|DERIVES_FROM*1..N]-` 同时取上下游 + 边投影；controller `/neighborhood` 改调它、透传 `granularity`。`FlowEdgeView`/节点 `distinct()` 去重（FR-025 环/重复保护）。
- **Rationale**: 现状缺口纯在 controller 合并层丢边（底层 traverse 有边）。无向单查语义单一、边天然闭合、支持粒度参数（现写死 TABLE），优于「两次查询应用层合并」。
- **Alternatives**: 方案 A 仅在 controller 合并 up.edges∪down.edges —— 可行的最小改动，但两次查询且粒度写死，作为 fallback。

### D10. 两点间路径高亮

- **Decision**: 新增 `pathsBetween(tenant,project,from,to,depth)` + `GET /api/lineage/paths?from=&to=` + DTO `LineagePath(from,to,nodes,edges,pathExists,truncated)`。用有界变长 `[:...*1..N]->` + `LIMIT $pathCap`，投影**路径上节点∪边的去重集**（非每条路径明细，前端只做高亮）；无路径返回空集 + `pathExists=false`。
- **Rationale**: `allShortestPaths` 会漏非最短连接路径（FR-014 要「所有连接路径」）。Neo4j 变长路径默认不重复关系，天然防环（FR-025），叠 `depth≤20` clamp + `pathCap` 防爆炸。
- **Alternatives**: `allShortestPaths` —— 语义不符否决。

### D11. 节点富属性 —— MVP 限 Neo4j 可得，owner/tag 延后

- **Decision**: traverse/impact/search 的 table 节点 attrs 从 `{}` 富化为 Neo4j 内可得字段：`layer`（已在节点）、`producers`（`[(task:Task)-[:WRITES]->(end)|task.name]`）、`syncedRowsToday`（`SYNCED{bizDate=date()}` rowCount SUM，复用 `syncSummary` 逻辑）、`lastSyncDate`（max bizDate）。`owner`/`tag` **不在 Neo4j**（写侧 `ensureTable` 不写），MVP **不在图查询里跨库 join**——留到详情面板按需从 PG catalog 服务补取（或标记 follow-up）。`GraphNodeView.attrs` 是开放 map，**不改 DTO 结构**，仅填充。
- **Rationale**: 图查询跨 PG join owner/tag 会增加耦合与延迟；Neo4j 可得属性一条 pattern-comprehension 子查询即可，满足 FR-019「有则显示」的主体（层/任务/新鲜度/synced）。
- **Alternatives**: 写侧把 owner/tag 冗余进 Table 节点 —— 增加写路径耦合，推迟；读侧应用层批量回查 PG —— MVP 不做。

### D12. 过滤 —— 服务端 Cypher WHERE

- **Decision**: layer/type/confidence/source 过滤作为可空 Cypher 参数（`$x IS NULL OR ...`），节点过滤加在 traverse `WHERE`、边过滤加在 `edgeCypher` 的 `annotateCorrections` **之后**（CONFIRMED 回填后再筛 confidence）。前端过滤仅作纯视觉临时隐藏，不作数据正确性来源。
- **Rationale**: 前端过滤会「先截断 2000 再筛」漏数（截断在过滤前）；服务端过滤让 filter 参与上限计算（FR-024 截断语义正确）。参数化避免拼 Cypher 注入、利于 plan cache。
- **Alternatives**: 纯前端过滤 —— 与截断语义冲突，否决。

### D13. schema / 索引 / 测试基线

- **Decision**: **不动 PG `schema.sql` 与 `schema_version`（保持 0.7.0）**——血缘在 Neo4j，PG 仅只读既有 `catalog_node`/`tag`。MVP 走 CONTAINS 故**不改 `Neo4jSchemaInitializer`**（若后续启用 fulltext 再加）。测试：查询逻辑用真 Neo4j IT（仿 `LineageSeamE2EIT`：`Store 写→Neo4jLineageGraphReader→LineageQueryService 读`），扩 `seed-lineage.cypher` **补 `:Task-[:WRITES]->:Table` 边**及更多层/名以覆盖 search/富属性；REST 契约与跨项目隔离用 h2 profile shape 测（仿 `LineageGraphEndpointTest`，种子已含 tenant2/project2）。
- **Rationale**: 现有测试底座（Testcontainer / `NEO4J_TEST_URI` 直连 / h2 shape 双层）足够；Cypher 正确性只能真图验，桩 reader 验不了。
- **门禁/隔离**: 6 缺口全只读不经 PolicyEngine；新 search/impact 必须断言不返回 tenant2 资产（FR-022 回归）。

## 未决/推迟（记录，不阻塞 MVP）

- Neo4j fulltext 搜索索引（规模增长后）
- 节点 owner/tag 直出（写侧冗余或读侧 PG 回查）
- 指标血缘多跳/派生指标（现 `metricLineage` 仅 1 跳、metricType 硬编码 ATOMIC）——本特性不扩
- 导出格式（图片 vs 结构化）与深链落地细节 → Phase 1 data-model / tasks 细化
