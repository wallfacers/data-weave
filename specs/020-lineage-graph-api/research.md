# Phase 0 Research: 血缘查询、API 与企业级前端视图

**Feature**: 020-lineage-graph-api | **Date**: 2026-06-30

本特性 Technical Context 无 NEEDS CLARIFICATION（spec + 共享设计 §4/§7/§10 + CLAUDE.md 已锁定栈与约定）。以下为关键技术决策。

---

## D1 · Cypher 变长路径取代 Java 内存 BFS

**Decision**：上游/下游/邻域/影响面统一用 Cypher 变长路径 `[:FLOWS_TO|DERIVES_FROM*1..N]` 实现，运行在 018 提供的只读 neo4j 会话上。
- 下游：`MATCH (a {id:$id})-[:FLOWS_TO|DERIVES_FROM*1..$depth]->(d) WHERE a.tenantId=$t AND a.projectId=$p RETURN DISTINCT d`。
- 上游：方向反转 `<-[...]-`。
- 邻域：无向 `-[...*1..$depth]-`，前端分层下钻按此取局部子图。
- 影响面：下游全闭包（`depth` 取上界）+ 跨表/列双粒度（`FLOWS_TO` 表级、`DERIVES_FROM` 列级同在路径模式里）。

**Rationale**：换 neo4j 的核心收益即此——变长路径是图库原生能力，穿透任意跳数 join/CTE/union 的血缘链，取代现 `LineageGraphService` 在应用层手写的 BFS（O(图规模) 内存装配 + 多次 JDBC 往返）。Cypher 路径默认**不重复同一关系**，天然防环；再叠 `*..N` 上界二次防御。

**Alternatives rejected**：
- 保留 Java BFS、仅把存储换 neo4j：丢掉图库最大价值，仍需把全图捞进内存遍历，列级会爆。
- APOC `apoc.path.expandConfig`：能力更强但引入 APOC 插件依赖；MVP 用原生变长路径足够，APOC 留作未来更复杂遍历的优化项（记 future）。

---

## D2 · 有界查询与分页/截断策略

**Decision**：每个遍历查询带三道闸：
1. **深度上界** `depth`：请求可传，服务端 clamp 到 `MAX_DEPTH`（默认 20）；缺省给安全默认（如 upstream/downstream 默认全闭包但仍 ≤MAX_DEPTH）。
2. **节点数上界** `MAX_NODES`（默认 2000）：Cypher `LIMIT` + 服务端判断是否达上界。
3. **分页** `offset`/`limit`：结构下钻类（datasources / tables/{id}/columns）与大结果集影响面支持分页。

达上界即**截断**，返回结构带 `truncated:true` + `truncatedAt`，并 `log.warn`（含锚点 id、depth、被截断估计数）。前端显示"结果已截断，请缩小范围"。

**Rationale**：FR-004 + SC-004 硬要求有界、不一次性拉爆、截断不静默丢。WebFlux 下大结果集还会压垮序列化与前端渲染，前端的分层懒加载（展开才取子层）与此配合，避免首屏拉全图。

**Alternatives rejected**：无界全图 `*` —— 深链路/广扇出直接 OOM 或超时；流式游标返回 —— MVP 过度工程，分页+上界已满足。

---

## D3 · neo4j 不可达降级错误码

**Decision**：查询服务捕获 neo4j 驱动连接类异常（`ServiceUnavailableException` / `SessionExpiredException` / 连接超时），转抛 `BizException("lineage.store_unavailable")`，由 `GlobalExceptionHandler` 按 UI locale 渲染 message（zh/en 双语 properties 各加一条）。其余血缘查询异常按既有通用错误处理。**只血缘 API 受影响**，平台其余端点（ops/metrics/调度/run logs）与 neo4j 无耦合，100% 不受影响（SC-003）。

**Rationale**：FR-005 要求稳定错误码 + 友好降级 + 平台其余不受影响。走既有 `BizException + GlobalExceptionHandler` i18n 错误通道（CLAUDE.md i18n 规则③），前端 toast 信任后端 message，无中文兜底。

**Alternatives rejected**：返回 200 + 空图（前端无法区分"真无血缘"与"库挂了"，误导）；500 裸异常（无稳定码、无 i18n、前端无法友好降级）。

---

## D4 · 前端图渲染方案选型

**Decision**：MVP 用**自绘 SVG/CSS 分层布局 + 受控展开折叠**，不引入重型图库。
- 库/表/列三级 = 受控树（左侧栏，hugeicons 展开折叠图标），点表/列在右侧画布按"列 → 列"流绘制血缘流（SVG 折线/曲线）。
- 影响面高亮 = 选中节点后给可达集合加语义 token 高亮类（`bg-primary/10`、`ring`）。
- 指标叠加 = 节点旁徽标（Badge）显示指标血缘/今日同步行数。
- 分层懒加载：展开某节点才 fetch 其子层/邻域。

**Rationale**：DESIGN.md 约束语义 token、无 header/footer 分割线、shadcn base；自绘可严格遵守且 `pnpm typecheck` 可控、bundle 不膨胀。React 19 + 受控状态足以承载三级展开与有界子图。重型力导向图（cytoscape/reactflow）对"分层下钻 + 列级流"这种结构化场景反而布局难控、与设计系统冲突，留作未来大图可视化的可选增强（记 future）。

**Alternatives rejected**：
- reactflow / cytoscape.js：引入大依赖 + 自带样式与 DESIGN.md 语义 token 冲突，MVP 过重。
- d3-force 力导向：分层血缘更适合确定性分层布局，力导向布局抖动、不利下钻定位。

---

## D5 · 多粒度返回结构

**Decision**：所有节点统一 `GraphNodeView{ id, type(datasource|table|column|metric), name, layer, parentId?, attrs? }`；所有边统一 `FlowEdgeView{ from, to, granularity(table|column), taskDefId?, confidence?, transform? }`。端点按粒度组合返回：
- 结构下钻：`datasources`→datasource 节点列表；`tables/{id}/columns`→column 节点列表。
- 上下游：`{nodes:[GraphNodeView], edges:[FlowEdgeView], truncated, truncatedAt}`，`granularity` 决定走 `FLOWS_TO`(table) 还是含 `DERIVES_FROM`(column)。
- 影响面 `impact/{nodeId}`：`ImpactResult{ root, downstream:[GraphNodeView 分层分粒度], edges, truncated }`。
- 指标 `metrics/{id}/lineage`：`MetricLineage{ metric:GraphNodeView, sources:[GraphNodeView(table|column)], edges }`。
- 运行态 `sync-summary`：`SyncSummary{ syncedRows: Long? }`（null=无采集，前端"估算中"）。

**Rationale**：FR-008 要求返回携带粒度与节点类型供前端分层渲染。统一 node/edge 模型让前端一套渲染管线吃所有端点，列级与表级只差 `granularity` 与 `type`，降低前端分支复杂度。

**Alternatives rejected**：每端点各自异构 DTO（前端要写 N 套适配，易漂移）；把 neo4j 原始 `Record` 透出（耦合驱动类型、无租户裁剪、不稳定）。

---

## D6 · 与 018 的契约边界（对桩并行）

**Decision**：本特性只读，依赖 018 提供：① neo4j driver `@Bean` + 只读 `Session`/`ReactiveSession` 获取入口（dataweave-master/infrastructure）；② §4 图模型（节点标签/关系/唯一键/`tenantId,projectId` 属性与索引）；③ `:Table`/`:Column` 的图内稳定 id（PG `data_table` 已删，`/tables/{id}` 用图内 id）。018 未落地前，对**会话接口桩 + 图模型契约**开发与单测；集成时先合 018，跑共享 surface 测试（会话可用、图内 id 一致、租户属性存在）确认缝合，再认定闭环。

**Rationale**：共享设计 §10「先定契约、三份并行、各自 worktree」；CLAUDE.md 跨特性闭环规则——编译通过但 sibling 落地后 no-op 即未完成。

**Alternatives rejected**：等 018 完全落地再开工（串行，违背并行设计）；本特性自建 driver `@Bean`（与 018 重复/冲突，违反 V 内核复用）。
