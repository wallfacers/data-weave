# Implementation Plan: 血缘探索器入口重构——搜索优先 · 数据源降级为分面 · 跨库可辨

**Branch**: `054-lineage-search-first-nav` | **Date**: 2026-07-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/054-lineage-search-first-nav/spec.md`

## Summary

在 052 血缘探索器地基上，把入口从「数据源孤岛树优先」重构为**「搜索优先 + 数据源降级为分面 + 图上跨库可辨」**，并把「表/字段连线」补成真实连线。四块能力：

1. **搜索优先入口（P1）**：把 052 已实现但埋在工具栏里的 `/search`（`fetchSearch`）提为血缘探索器**视觉主入口**——首屏聚焦搜索、空态引导「搜一个资产开始」，不必先选库即可锚定任意表/列/指标。**零后端新增**（`/search` 已具备；仅富化候选的数据源展示名）。
2. **图上跨库可辨（P1）**：读侧节点投影补齐**所属数据源 id/name**（数据已在图库：`:Datasource{name}-[:HAS_TABLE]->:Table{datasourceId}`，只是没投影）；前端节点渲染数据源徽标、边按两端 datasourceId 差异判定**跨源/库内/未知**并以区别样式强调 + 图例。
3. **表/字段连线（P1）**：052 现状列内联在表节点、列级 `DERIVES_FROM` 边被 `toggleColumns` 丢弃。本特性**保留列级边并渲染成真实连线**——给列行加**列级 Handle 锚点**（id=列 id），`lineageToFlow` 产出带 `sourceHandle/targetHandle` 的列→列边，与表→表连线并存且视觉可区分；跨库字段映射同走跨源样式。
4. **数据源浏览分面（P3，可选）**：把左侧「唯一数据源树」降级为可切分面（数据源/分层/最近，**不含 ownership**）；若交付，顺带补 052 遗留的 `tables-by-datasource` 占位（新增只读端点）。

全部改动落在**读侧查询投影 + 运行态观测/展示层**，不触碰调度内核、executor、写闸门、PG schema、neo4j 写侧与既有 052 查询语义，符合 constitution「内核复用」。

## Technical Context

**Language/Version**: Java 25（backend）· TypeScript / React 19 / Next.js 16（frontend）

**Primary Dependencies**: Spring Boot 4.0 / WebFlux · Neo4j Java driver（血缘唯一存储）· `@xyflow/react ^12`（复用，含多 Handle/handle-specific edges 能力）· `@dagrejs/dagre`（052 已引入，复用）· shadcn/ui + hugeicons

**Storage**: Neo4j（血缘图，唯一存储）——**只改读侧投影 Cypher，不改写侧、不新增索引、不 bump schema**；数据源归属数据（`:Datasource.name`、`:Table.datasourceId`、`[:HAS_TABLE]`）已存在于图库，仅需投影出来。PostgreSQL / `schema_version` **不动**。

**Testing**: JUnit 5 + AssertJ；真 Neo4j IT（`Neo4jTestSupport` Testcontainer / `NEO4J_TEST_URI` 直连，seed 跨数据源 + 列级边）+ h2 profile REST shape 契约 + 跨项目隔离回归；前端 vitest（layout 跨源分类 + 列级边产出）+ Playwright 浏览器门（搜索优先入口、节点徽标、跨源边、字段连线）

**Target Platform**: Web（workspace view `lineage`）

**Project Type**: Web application（前后端双项目）

**Performance Goals**: 沿用 052 上限 `MAX_NODES=2000`/`MAX_DEPTH=20`；数据源徽标与跨源判定为 O(节点/边) 前端计算，不引额外查询；列级连线仅在表展开时按需拉取（复用 `expandColumns`），不预加载全图列。

**Constraints**: reuse-first 硬规则（搜索输入/下拉/滚动/加载/刷新/卡片/图标/分段控件全走规范组件，图渲染沿用 `@xyflow/react`，禁手写同类原语）；无分割线布局；语义 token；项目隔离零泄漏（沿用 `TenantContext`+`project()`）；所有查询只读、不经 PolicyEngine（写仍走既有 `/corrections` 闸门）；**不改 052 既有查询返回语义**（仅在节点投影追加字段，向后兼容）。

**Scale/Scope**: 后端 3 处节点投影 Cypher 追加数据源字段 + `SearchCandidate` 富化数据源名 +（P3 可选）1 个新只读端点；前端血缘 view 入口重构（搜索提主入口 + 分面浏览降级）+ 节点徽标 + 边跨源样式 + 列级 Handle/连线渲染 + 图例；i18n 双写补键。

## Constitution Check

*GATE: 必须在 Phase 0 前通过；Phase 1 后复检。*

| 原则 | 结论 | 依据 |
|---|---|---|
| **I. Files-First** | ✅ 不适用/不违反 | 本特性是血缘**查询投影与展示**，不改任务/工作流文件定义契约 |
| **II. Server is Source of Truth** | ✅ 不违反 | 无双向同步；搜索/分面/图解析严格项目隔离，沿用 `TenantContext`+`project()`，Cypher 恒带 `tenantId/projectId` |
| **III. Two-Legged Debugging** | ✅ 不适用 | 不触碰 CLI/本地运行时/executor |
| **IV. AI Lives in Local Agent** | ✅ 不违反 | 不新增服务端 AI；血缘图属 ops 观测面，本特性**增强**该面（拆除不得损伤观测的另一面），与内核一致 |
| **V. Reuse the Kernel** | ✅ 遵守 | 复用 052 血缘查询服务/Neo4j reader/图渲染栈/搜索端点；**全部新查询只读**，写操作仍经 PolicyEngine（`/corrections` 不变），无 gate 绕过；节点投影仅追加字段，向后兼容 |

**门禁结果：PASS**，无违规，`Complexity Tracking` 留空。无新增第三方依赖（多 Handle/handle-specific edge 是 `@xyflow/react` 既有能力）。

**跨特性核对**：与 **053**（血缘 LLM Agent 抽取 + 实时 Schema）无重叠面——053 改**写侧/抽取通道**，054 改**读侧投影/前端展示**；共享只读的 `GraphNodeView`/`FlowEdgeView` DTO，054 仅**向后兼容追加字段**（datasourceId/datasourceName 进 attrs 或新增可空字段），不改既有字段语义，集成时先落地方为准回归共享读模型。052 为直接地基（已 merge main），本特性在其上增量。

## Project Structure

### Documentation (this feature)

```text
specs/054-lineage-search-first-nav/
├── plan.md              # 本文件
├── spec.md              # 特性规范（已含 Clarifications）
├── research.md          # Phase 0：技术决策（字段连线渲染/数据源富化/跨源判定/搜索优先/Deferred 定案）
├── data-model.md        # Phase 1：DTO 富化 / 列级 Handle 模型 / 跨源分类 / 分面类型
├── quickstart.md        # Phase 1：端到端验证指南
├── contracts/
│   └── lineage-explorer-v2-api.md   # 读侧契约（节点投影富化 + 可选 tables-by-datasource）
└── checklists/requirements.md       # 规范质量清单（已通过）
```

### Source Code (repository root)

```text
backend/
  dataweave-master/src/main/java/com/dataweave/master/
    application/LineageQueryService.java
        # 改：节点投影（traverse / neighborhood / impact / pathsBetween 的 RETURN）追加
        #     t.datasourceId + join :Datasource 取 name → 进 mapNode 的 attrs（datasourceId/datasourceName）
        # 改：search() 的 SearchCandidate 富化——补 datasource 展示名（当前只有 datasourceId）
        # 加（P3 可选）：tablesByDatasource(tenantId,projectId,dsId,offset,limit) → List<GraphNodeView>
    lineage/
      GraphNodeView.java        # 不改结构（datasource 走 attrs，向后兼容）；或按 research 决定加可空字段
      SearchCandidate.java      # 改：+datasourceName（可空，向后兼容）
      FlowEdgeView.java         # 不改（跨源判定在前端呈现层做）
    infrastructure/lineage/
      Neo4jLineageGraphReader.java  # 不改（继续执行 service 传入的 Cypher）
  dataweave-api/src/main/java/com/dataweave/api/interfaces/
    LineageGraphController.java # 改（P3 可选）：+ GET /datasources/{id}/tables
  dataweave-master/src/test/
    java/.../LineageDatasourceProjectionIT.java   # 新增：真 Neo4j IT——节点带 datasourceId/name、跨源 seed、search 带 name、tables-by-ds
    resources/lineage-test/seed-lineage.cypher    # 改：补跨数据源链（mysql→hive→pg）+ 列级 DERIVES_FROM
  dataweave-api/src/test/.../LineageGraphEndpointTest.java  # 改：新字段/新端点 h2 shape 契约 + 隔离回归

frontend/
  lib/lineage-api.ts
      # 改：GraphNodeView TS 类型/readNodeAttrs 补 datasourceId/datasourceName；SearchCandidate 补 datasourceName
  lib/workspace/
    lineage-layout.ts
        # 改：lineageToFlow 边样式分支加「两端 datasourceId 不同 → 跨源」判定与样式（与 inferred/confirmed/highlight 正交）
        # 改：产出列级连线——从展开的列级边生成带 sourceHandle/targetHandle 的 ReactFlow 边
        # 改：nodeSize/列渲染保留内联，列行暴露稳定 handle 锚点位
    lineage-graph.ts
        # 改：reducer 保留列级 DERIVES_FROM 边（新增 columnEdgesByTable 或并入 edges 带标记），
        #     collapseColumns 时同步移除；不再在 view 层丢弃列级边
    lineage-datasource-style.ts   # 新增：datasourceId → 稳定配色/缩写 的确定性工具（配色耗尽以文本兜底）
  components/workspace/nodes/
    lineage-node.tsx              # 改：头部加数据源徽标（图标/名/色）；列行加列级 Handle（source 右 / target 左，id=列 id）
    lineage-node-types.ts         # 改：LineageNodeData 补 datasourceId/datasourceName；列项补 handle 需要的稳定 id
  components/workspace/views/
    lineage-view.tsx
        # 改：入口重构——未锚定时搜索为主入口 + 空态「搜一个资产开始」；toggleColumns 保留列级边
    views/lineage/
      lineage-toolbar.tsx / (新) lineage-search-hero.tsx   # 搜索提为主入口（hero + 常驻）
      lineage-tree.tsx → (改/新) lineage-facets.tsx        # 数据源树降级为可切分面（数据源/分层/最近）；P3 可选
      lineage-legend.tsx          # 改：加「跨源边 / 库内边 / 未知来源 / 数据源徽标」图例
  messages/{zh-CN,en-US}.json     # 改：lineageView 命名空间补键（双写对齐）
  DESIGN.md                       # 改（若新增分面切换/徽标原语）：回填组件目录
```

**Structure Decision**: 沿用 052 双项目结构与 DDD 分层与 workspace view `lineage` 注册键（不动）。后端改动集中在 `dataweave-master/application`（投影 Cypher 追加字段）+ 少量 DTO 富化 +（可选）1 端点；前端集中在 `lib/workspace`（layout/reducer/样式工具）+ `components/workspace/nodes`（节点徽标/列级 handle）+ 血缘 view 入口重构 + 图例。**优先级切分**：US1 搜索优先 + US2 跨库可辨（含表/字段连线）为 P1 核心，US3 分面浏览 + US4 泳道为 P3 可裁剪——tasks 阶段据此分层，P1 独立可交付。

## Complexity Tracking

> 无 Constitution 违规，本节留空。
