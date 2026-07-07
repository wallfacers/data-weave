---
description: "Task list for 052-lineage-graph-explorer"
---

# Tasks: Lineage Graph Explorer（血缘图探索器）

**Input**: Design documents from `specs/052-lineage-graph-explorer/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/lineage-query-api.md

**Tests**: 必含（CLAUDE.md「无测试=未完成」）。后端真 Neo4j IT + h2 shape 契约；前端 vitest + Playwright 浏览器门。

**Organization**: 按用户故事分阶段；每个实现任务标注轨道 **(BE)** 后端 / **(FE)** 前端，供两名外部 agent 分工。

## Format: `[ID] [P?] [Story] (轨道) Description + 文件路径`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1..US5
- **(BE)/(FE)**: 轨道归属 —— **Agent A = 全部 (BE)**，**Agent B = 全部 (FE)**

## 双轨分工总览（关键）

| 轨道 | Agent | 负责任务 | 触碰路径（唯一） |
|---|---|---|---|
| 后端查询补齐 | **Agent A** | T002, T004–T007, T014–T018, T025–T027, T030–T033, T037, T047 | `backend/**` |
| 前端富化改造 | **Agent B** | T001, T008–T013, T019–T024, T028–T029, T034–T036, T038–T046 | `frontend/**` |
| 集成/评审/兜底 | **主 Claude（我）** | T048–T050 | 跨轨联调 + 最终 review |

**硬约束**：Agent A 只提交 `backend/**`，Agent B 只提交 `frontend/**`；**禁止 `git add -A`**、禁止改对方 lane 文件（含 `specs/`、`docker-compose.yml`、`frontend/components/workspace/run-logs-tabs.tsx` 等他人未提交改动）。契约冻结在 `contracts/lineage-query-api.md`，两轨可完全并行，集成由主 Claude 兜底。

---

## Phase 1: Setup（共享基建）

- [ ] T001 [P] (FE) 新增前端依赖 `@dagrejs/dagre` 到 `frontend/package.json` 并 `pnpm install`（血缘分层布局，research D2）
- [ ] T002 [P] (BE) 冒烟现有 Neo4j 测试底座：跑 `backend` 的 `LineageSeamE2EIT` 确认 Testcontainer / `NEO4J_TEST_URI` 可用（后续 IT 基线）

---

## Phase 2: Foundational（阻塞前置，两轨各自 checkpoint）

**⚠️ 各轨的 US 任务依赖本轨 Foundational 完成。**

### 后端基座（Agent A）
- [ ] T004 [P] (BE) 新增 DTO `SearchCandidate` in `backend/dataweave-master/src/main/java/com/dataweave/master/lineage/SearchCandidate.java`（data-model：id/type/name/layer/datasource）
- [ ] T005 [P] (BE) 新增 DTO `LineagePath` in `backend/dataweave-master/.../lineage/LineagePath.java`（from/to/nodes/edges/pathExists/truncated）
- [ ] T006 (BE) 扩展 `ImpactResult`（`backend/dataweave-master/.../lineage/ImpactResult.java`）：新增 `reachableTotal`+`totalIsLowerBound`，`edges` 保持结构（后续填充）
- [ ] T007 (BE) 扩展种子 `backend/dataweave-master/src/test/resources/lineage-test/seed-lineage.cypher`：补 `:Task-[:WRITES]->:Table` 边 + 更多 layer/qualifiedName（供 search/富属性/producers 断言）

### 前端基座（Agent B）
- [ ] T008 [P] (FE) 从 `DagDialogInner` 抽出 `frontend/components/workspace/flow-canvas-with-panel.tsx`（画布 + 可拖拽宽度嵌入面板 + Escape 关面板），并改 `dag-dialog.tsx` 复用之（工作流行为零回归）
- [ ] T009 [P] (FE) 新建共享原语 `frontend/components/ui/segmented.tsx` + **回填 `frontend/DESIGN.md` 原语速查表 + 深章节**（方向/粒度分段）
- [ ] T010 [P] (FE) 新建共享原语 `frontend/components/ui/stepper.tsx` + **回填 `frontend/DESIGN.md`**（深度 −N+）
- [ ] T011 [P] (FE) 新建血缘节点 `frontend/components/workspace/nodes/lineage-node.tsx` + `nodes/lineage-node-types.ts`（`LineageNodeData`：type/layer色/新鲜度/synced/可展开列，Handle 左 target 右 source）
- [ ] T012 [P] (FE) 新建 `frontend/lib/workspace/lineage-layout.ts`：dagre `rankdir=LR` 纯函数 `lineageToFlow(graph)→{nodes,edges}`
- [ ] T013 [P] (FE) 新建 `frontend/lib/workspace/lineage-selection-store.ts`（selectedNode/selectedEdge/panelTab，不复用 `useNodeDetailStore`）

**Checkpoint**：两轨基座就绪 → US 任务可并行。

---

## Phase 3: User Story 1 — 真实图引擎双向探索（P1）🎯 MVP

**Goal**: 分层自动布局、缩放/平移/自适应、上/下/双向、可控深度、原地增量展开、选中详情面板。

**Independent Test**: 见 quickstart「US1」行——三栏 + xyflow 画布（非 SVG）、上游左下游右、双向连边完整、原地展开保留视图。

### 后端（Agent A）
- [ ] T014 (BE) `Neo4jLineageGraphReader` traverse 节点投影富化：table attrs = `{layer,producers,syncedRowsToday,lastSyncDate}`（research D11，pattern comprehension 子查询）
- [ ] T015 (BE) `LineageQueryService.neighborhood(...)` 下沉服务层：无向 `-[:FLOWS_TO|DERIVES_FROM*1..N]-` 双向带边 + `granularity` 参数；`LineageGraphController` `/tables/{id}/neighborhood` 改调它（去 `List.of()` 丢边）
- [ ] T016 (BE) traverse/neighborhood 加服务端可空过滤 `layers/types/confidences/sources`（`$x IS NULL OR ...`，边过滤在 `annotateCorrections` 之后）
- [ ] T017 [P] (BE) 真 Neo4j IT（仿 `LineageSeamE2EIT`）：neighborhood 双向带边闭合、富属性字段、过滤生效、跨项目零泄漏
- [ ] T018 [P] (BE) h2 shape 契约（仿 `LineageGraphEndpointTest`）：neighborhood/upstream/downstream 新参数解析 + 空态 + `project.required`

### 前端（Agent B）
- [ ] T019 (FE) 重写 `frontend/components/workspace/views/lineage-view.tsx` 为三栏（`LineageTree` | `FlowCanvasWithPanel`(画布=`DagRenderer`+`lineageNodeTypes`) | 详情面板`DetailPanelShell`），经 `lineage-layout` 布局
- [ ] T020 (FE) 工具栏 `views/lineage/lineage-toolbar.tsx`：方向 `Segmented`(上/下/双向) + 深度 `Stepper`，接 `lineage-api` 的 `fetchUpstream/fetchDownstream/neighborhood`；`ViewRefreshControl` 右置
- [ ] T021 (FE) 原地增量展开/收起：点节点追加邻居、保留既有节点与视图位置（`expandedNodeIds`），环去重（FR-005/025）
- [ ] T022 (FE) 选中节点 → 高亮直接连边 + `DetailPanelShell` 节点 Tab 显属性（层/任务/新鲜度/synced）
- [ ] T023 (FE) 删除/退役手绘 SVG `frontend/components/workspace/views/lineage/lineage-flow.tsx`（改由画布替代，确认无其他引用）
- [ ] T024 [P] (FE) vitest：`lineage-layout` 分层正确性 + 选中 store；Playwright 浏览器门覆盖 US1 验收

**Checkpoint**: US1 独立可用（MVP）。

---

## Phase 4: User Story 2 — 图内搜索与定位（P2）

**Goal**: 按名搜资产 → 候选带层/类型标注 → 选中以其为锚点加载并聚焦居中。

**Independent Test**: 输名字片段命中候选、选中后图居中高亮该锚点、无匹配空态。

### 后端（Agent A）
- [ ] T025 (BE) `LineageQueryService.search(tenant,project,keyword,types,offset,limit)`：多标签 UNION `toLower CONTAINS`，`YIELD` 后强制项目隔离，`clampLimit`（research D7）
- [ ] T026 (BE) `LineageGraphController` 新增 `GET /api/lineage/search`（q/types/offset/limit），返回 `List<SearchCandidate>`，空态 `[]`
- [ ] T027 [P] (BE) IT：search 中缀命中（`order_detail`）+ 跨项目零泄漏（不返回 tenant2）；h2 shape 空态契约

### 前端（Agent B）
- [ ] T028 (FE) 工具栏搜索 `Input` → 候选列表（type/layer 标注）→ 选中 → 以其为锚点 `loadFlow` 并居中/标为锚点；无匹配空态（`LoadingState` 区分）
- [ ] T029 [P] (FE) Playwright 浏览器门覆盖 US2

**Checkpoint**: US1+US2 独立可用。

---

## Phase 5: User Story 3 — 影响分析与路径高亮（P2）

**Goal**: 影响 blast radius 高亮节点+路径边 + 真实可达计数；两点间路径高亮。

**Independent Test**: 影响分析图上高亮受影响节点及连边 + 「受影响 N 个」真实总数；A→B 路径高亮，无路径提示。

### 后端（Agent A）
- [ ] T030 (BE) `LineageQueryService.impact()` 填充 `edges`（照 traverse edgeCypher，闭合于 downstream 集，经 `annotateCorrections` 剔 REMOVED）
- [ ] T031 (BE) `impact()` 加独立 COUNT：`reachableTotal` + `totalIsLowerBound`（`countCap` 保护，research D8）
- [ ] T032 (BE) `LineageQueryService.pathsBetween(...)` + `GET /api/lineage/paths?from=&to=&depth=`：有界变长 + `pathCap`，节点∪边去重，无路径 `pathExists=false`（research D10）
- [ ] T033 [P] (BE) IT：impact edges 闭合 + reachableTotal 与分页解耦 + 达 cap 下限；paths 多路径去重 + 无路径

### 前端（Agent B）
- [ ] T034 (FE) 影响分析：图上高亮受影响节点+边、面板影响 Tab 显 `reachableTotal` 徽标（区别当前页 `nodeCount`）；迁 `impact-panel.tsx` 逻辑进 `DetailPanelShell`
- [ ] T035 (FE) 两节点路径高亮：选 from/to → `/paths` → 高亮路径节点+边；无路径明确提示
- [ ] T036 [P] (FE) Playwright 浏览器门覆盖 US3

**Checkpoint**: US1–US3 独立可用。

---

## Phase 6: User Story 4 — 列级血缘与边语义编码（P3）

**Goal**: 表节点展开列 + 列级派生边 + 可信度/加工/来源边样式 + 图例；表/列粒度切换。

**Independent Test**: 展开列见列间派生边；不同可信度/来源边样式可辨 + 图例；粒度切换锚点保持。

### 后端（Agent A）
- [ ] T037 (BE) 校验/补齐列级 traverse（`granularity=column` 的 `DERIVES_FROM` 上下游）与列过滤，补 IT 断言列级边字段（transform/confidence/source）齐全

### 前端（Agent B）
- [ ] T038 (FE) 表节点「展开列」内联列清单 + 列到列派生边渲染；粒度 `Segmented`(表/列) 切换保持锚点
- [ ] T039 (FE) 边样式编码（confidence/transform/source）+ 图例组件 + 面板边 Tab（迁 `edge-detail-panel.tsx` 人工修正 confirm/remove/revoke 进 `DetailPanelShell`，保留 `project:manage` 门禁与 outcome 三态分流）
- [ ] T040 [P] (FE) Playwright 浏览器门覆盖 US4

**Checkpoint**: US1–US4 独立可用。

---

## Phase 7: User Story 5 — 节点富信息与视图可分享（P3）

**Goal**: 节点富属性展示 + 导出 + 深链恢复视图状态。

**Independent Test**: 节点详情显层/任务/新鲜度/synced；深链新开恢复锚点/方向/深度；导出当前子图。

### 前端（Agent B）
- [ ] T041 (FE) 详情面板富属性渲染（层/产出任务/新鲜度/今日 synced rows；owner/tag 若详情按需从 catalog 补取，否则标 follow-up）
- [ ] T042 (FE) 深链：`ViewState`（anchor/direction/depth/granularity/filters）编码进 URL query + 进入视图恢复（对齐 `?open=lineage`）
- [ ] T043 (FE) 导出当前聚焦子图（图片或结构化 JSON），可分享
- [ ] T044 [P] (FE) Playwright 浏览器门覆盖 US5

**Checkpoint**: US1–US5 全部独立可用。

---

## Phase 8: Polish & 跨切面

- [ ] T045 [P] (FE) i18n：`lineageView` 命名空间新键补齐，`frontend/messages/{zh-CN,en-US}.json` 双 bundle 键集对齐（CI 可静态解析）
- [ ] T046 [P] (FE) 前端门禁：`pnpm typecheck` 零错 + `pnpm design:lint`（DESIGN.md 改动）过
- [ ] T047 [P] (BE) 后端全量：`./mvnw -pl dataweave-master,dataweave-api -am test` 绿（setsid 脱离）
- [ ] T048 (主 Claude) 集成联调：前端接真后端新端点（search/neighborhood 带边/impact edges+count/paths/富属性），修契约漂移
- [ ] T049 (主 Claude) 跑 `quickstart.md` 端到端验证 + reuse-first 自查清单
- [ ] T050 (主 Claude) 边缘态兜底：`lineage.store_unavailable` 降级、截断提示（MAX_NODES/countCap/pathCap）、跨项目隔离回归——覆盖全 US

---

## Dependencies & Execution Order

### 轨内顺序
- **Agent A（BE）**: T002 → T004/T005/T006/T007（并行）→ US1(T014→T015→T016, T017/T018) → US2(T025→T026, T027) → US3(T030→T031→T032, T033) → US4(T037) → T047
- **Agent B（FE）**: T001 → T008/T009/T010/T011/T012/T013（并行）→ US1(T019→T020→T021→T022→T023, T024) → US2(T028, T029) → US3(T034→T035, T036) → US4(T038→T039, T040) → US5(T041→T042→T043, T044) → T045/T046

### 跨轨
- 两轨在契约（`contracts/lineage-query-api.md`）冻结下**完全并行**；前端可先对既有端点 + 契约打桩，主 Claude 在 T048 做真端点联调兜底。
- **T048–T050 由主 Claude 在两轨完成后执行**（集成 + 评审 + 兜底）。

### 并行机会
- Phase 2 内 T004–T007（BE）与 T008–T013（FE）全部 [P]，跨轨天然并行。
- 每个 US 的 BE IT（T017/T027/T033）与 FE Playwright（T024/T029/T036）标 [P]，本轨内可并行。

---

## Parallel Example（Phase 2 基座）

```bash
# Agent A（后端，同时起 4 个 DTO/种子任务）
T004 SearchCandidate.java · T005 LineagePath.java · T006 ImpactResult 扩展 · T007 seed-lineage.cypher

# Agent B（前端，同时起 6 个基座）
T008 FlowCanvasWithPanel · T009 Segmented · T010 Stepper · T011 lineage-node · T012 lineage-layout · T013 selection-store
```

---

## Implementation Strategy

### MVP 优先（US1）
1. Setup（T001/T002）→ 2. 各轨 Foundational（T004–T013）→ 3. US1（T014–T024）→ **停下验 US1 独立可用（MVP）**。

### 增量交付
US1 → US2 → US3 → US4 → US5，每故事独立测试、不破坏前序；主 Claude 每轮做集成兜底。

### 两 Agent 并行策略
- Agent A 走 BE 竖切，Agent B 走 FE 竖切，严格 lane 隔离（各只提交自己目录）。
- 主 Claude：契约看门 + T048–T050 集成/评审/兜底 + 冲突仲裁（发现越界改动立即 STOP 上报，不静默覆盖）。

---

## Notes
- [P] = 不同文件、无未完成依赖。
- **Lane 隔离硬规则**：`git add backend/...` / `git add frontend/...`，永不 `git add -A`；不碰他人未提交改动（`run-logs-tabs.tsx`、`docker-compose.yml`、`specs/`）。
- 后端 6 缺口全只读，不经 PolicyEngine；不动 PG `schema.sql` / 不 bump `schema_version`。
- 每任务或逻辑组完成即提交；WSL2 长跑测试用 `setsid` 脱离。
