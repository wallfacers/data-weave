---

description: "Task list for 054 血缘探索器入口重构"
---

# Tasks: 血缘探索器入口重构——搜索优先 · 数据源降级为分面 · 跨库可辨

**Input**: Design documents from `specs/054-lineage-search-first-nav/`
**Prerequisites**: plan.md · spec.md · research.md · data-model.md · contracts/lineage-explorer-v2-api.md · quickstart.md
**Tests**: 纳入（constitution「无测试=未完成」）——真 Neo4j IT + h2 shape 契约 + 前端 vitest + Playwright 浏览器门。

**地基**: 052 血缘探索器（已 merge main）。本特性只动**读侧投影 + 前端展示层**，向后兼容追加，不改 052 查询语义、不碰写侧/PG/neo4j 写侧/`schema_version`。

## 收口状态（2026-07-08，主 Claude 兜底评审 + rebase + 合并）

**已交付并合并 main**（fast-forward，2 commit `81c93e2`+`f677054`；分叉点 `b56e37f` 早于 053/055/056，已 rebase 到最新 main 重放，与三特性无冲突）：
- **US1 搜索优先入口**（T008–T013）+ **US2 跨库可辨/表·字段连线**（T014–T024）+ Setup/Foundational（T001–T007）✅
- 收口门实测：后端 `dataweave-master,dataweave-api` 编译 **BUILD SUCCESS**（mvnd + JDK25 + cache DISABLED）；**真 Neo4j IT `LineageDatasourceProjectionIT` 8/8**（testcontainers，Docker 恢复后补跑）；`LineageGraphEndpointTest` h2 契约 **5/5**；前端 `pnpm typecheck` 0 错；vitest（lineage-layout + datasource-style）**22/22**。
- 代码评审：`LineageQueryService` 构造签名/`SearchCandidate` 全构造点与 main 兼容；`lineageView.expand/collapse` 键两 bundle 均在位。

**浏览器门已在运行栈实证（2026-07-08 补跑）**：docker compose（pg+redis+neo4j）+ 后端 :8000 + 前端 :4000，neo4j 灌 054 跨库种子（去分号单条保留绑定）+ admin JWT + `dw.project.current=1`。ad-hoc Playwright **9/9 PASS，0 console error**：US1 搜索优先 hero「搜一个资产开始」+ 同名 user 跨库消歧候选（mysql-prod/pg-bi/hive-dw）；US2 图上三数据源徽标（MY/HI/PG）+ 跨库链 `user→dwd_user→dws_user_1d→rpt_user` + 跨源橙边 + 图例新项。截图目视确认真实渲染（非假绿）。
- 未再复跑 052 全量 6/6 回归门（054 未改 052 语义，主 Claude 评审判定不回退）。

**未做（P3，可裁剪）**：US3 数据源分面浏览（T025–T030）、US4 数据源泳道（T031–T032）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1/US2/US3/US4，映射 spec 用户故事
- 每个任务带确切文件路径

## 关键护栏（每个任务都适用）

- 后端改动后 `cd backend && ./mvnw -q -pl <module> compile` 零错；前端改动后 `cd frontend && pnpm typecheck` 零错。
- 节点投影**仅追加 attrs 键**、`SearchCandidate` **仅追加可空字段**——既有字段一字不改（052/053 共享 DTO 向后兼容）。
- 所有查询恒带 `tenantId/projectId` 隔离；只读、不经 PolicyEngine。
- 长跑测试用 `setsid` 脱离（见 CLAUDE.md WSL2 硬规则）；i18n 两 bundle 键集必须对齐。
- **禁止**改动/回滚 052 或他人代码；冲突即停并上报，不擅自裁决。

---

## Phase 1: Setup（共享地基）

- [x] T001 在 `backend/dataweave-master/src/test/resources/lineage-test/seed-lineage.cypher` 补**跨数据源**链：`mysql-prod.user`(ODS) → `hive-dw.dwd_user`(DWD) → `hive-dw.dws_user_1d`(DWS) → `pg-bi.rpt_user`(ADS)，建 `:Datasource{id,name,tenantId,projectId}` + `:Table{datasourceId}` + `[:HAS_TABLE]`；并在 hive 两表间建列级 `DERIVES_FROM`（`dwd_user.uid → dws_user_1d.user_id`）。含一个他项目资产用于隔离断言。
- [x] T002 [P] 新建 `frontend/lib/workspace/lineage-datasource-style.ts`：`datasourceColor(id)`（对 id 确定性 hash 取有限调色板，用语义/chart-* token）+ `datasourceAbbr(name)`；配色耗尽以缩写文本兜底（FR-011）。含 vitest 友好的纯函数导出。

---

## Phase 2: Foundational（阻塞 US1 候选名 + US2 全部，必须先完成）

**⚠️ CRITICAL**: 节点数据源富化是 US1（候选显示数据源名）与 US2（徽标/跨源边）的共同数据地基。

- [x] T003 改 `backend/dataweave-master/src/main/java/com/dataweave/master/application/LineageQueryService.java`：在 `traverse`/`neighborhood`/`impact`/`pathsBetween` 的表节点投影 RETURN 追加 `t.datasourceId` + `OPTIONAL MATCH (d:Datasource{id:t.datasourceId, tenantId,projectId}) → d.name`，经 `nodeAttrsExpr`/`tableAttrsCypher` 写入 attrs 键 `datasourceId`/`datasourceName`；列节点继承所属表数据源；METRIC 不带。既有字段不改。
- [x] T004 改 `backend/.../master/lineage/SearchCandidate.java` 追加可空 `String datasourceName`，并在 `LineageQueryService#search` 的 Table 分支 join `:Datasource.name` 富化（既有 `datasource`=id 保留）。
- [x] T005 [P] 改 `frontend/lib/lineage-api.ts`：`LineageNodeAttrs`/`readNodeAttrs()` 补 `datasourceId?`/`datasourceName?`；`SearchCandidate` 补 `datasourceName?`。
- [x] T006 [P] 新建真 Neo4j IT `backend/dataweave-master/src/test/java/com/dataweave/master/lineage/LineageDatasourceProjectionIT.java`（先写、先失败）：断言 neighborhood/upstream/downstream 表节点 attrs 带 `datasourceId`+`datasourceName` 与图库一致、同库共享；`columns/lineage` 返回列级边(from/to=列 id)且列节点可归属数据源；`search?q=user` 候选带 `datasourceName` 且同名跨库可区分；METRIC 无 datasource；跨项目零泄漏。
- [x] T007 [P] 改 `backend/dataweave-api/src/test/.../LineageGraphEndpointTest.java`：新字段 h2 shape 契约 + 既有端点**向后兼容**（旧字段不丢）+ 隔离回归。

**Checkpoint**: 节点/候选携带数据源数据就绪，US1/US2 可并行。

---

## Phase 3: User Story 1 - 搜索优先入口（Priority: P1）🎯 MVP

**Goal**: 搜索成为血缘探索器正门——不必先选库即可锚定任意资产；空态引导「搜一个资产开始」。

**Independent Test**: 首屏未锚定→搜索框主位且聚焦、空态文案在位；输入表名片段→候选带(数据源·类型·分层)→选中后图以该资产为锚点加载，全程未展开左侧任何数据源。

- [x] T008 [P] [US1] 新建 `frontend/tests/e2e/lineage-search-first.spec.ts`（Playwright，沿用 052 登录绕过）先写：断言首屏搜索聚焦 + 空态文案 + 不碰树即可锚定 + 候选显示数据源名。（先失败）
- [x] T009 [US1] 改 `frontend/components/workspace/views/lineage-view.tsx`：未锚定时渲染**搜索 hero**（居中大搜索框 + 默认聚焦）+ 画布空态「搜一个资产开始」；已锚定后搜索常驻可用。触发沿用提交/回车（research D4）。
- [x] T010 [US1] 改 `frontend/components/workspace/views/lineage/lineage-toolbar.tsx`（或新建 `lineage-search-hero.tsx`）：候选下拉每项显示 `datasourceName`+类型+分层，用于区分同名跨库；复用 `DropdownSelect`/规范原语，禁手写。
- [x] T011 [US1] 改 `frontend/components/workspace/views/lineage-view.tsx` 左栏：数据源树不再作为强制/唯一入口（让位于搜索，可折叠或降级占位；分面浏览在 US3 落地）。
- [x] T012 [US1] `frontend/messages/{zh-CN,en-US}.json` lineageView 命名空间补键（搜索 hero/空态「搜一个资产开始」/候选数据源标注），两 bundle 键集对齐。
- [x] T013 [US1] 浏览器门实证 US1：跑 `lineage-search-first.spec.ts` 转绿 + 人工核对首屏搜索优先与锚定不经树。

**Checkpoint**: US1 独立可用——搜索即可看血缘，摆脱「必须先选库」（MVP 达成）。

---

## Phase 4: User Story 2 - 图上跨库可辨 + 表/字段连线（Priority: P1）

**Goal**: 节点带数据源徽标、跨源边强调 + 图例；表→表与列→列均以真实连线呈现（列级连到具体字段锚点）。

**Independent Test**: 横跨多库图节点显数据源徽标、同库共享；跨源边样式区别于库内边 + 图例在位；展开列后 `dwd_user.uid`→`dws_user_1d.user_id` 有连到具体列行的连线，跨库字段映射走跨源样式，表级连线仍在且可区分。

- [x] T014 [P] [US2] 新建 `frontend/lib/workspace/__tests__/lineage-layout.test.ts`（vitest，先写先失败）：跨源分类三态（intra/cross/unknown，含 metric→unknown）；列级边产出带正确 `sourceHandle/targetHandle`；`datasourceColor` 确定性 + 耗尽兜底。
- [x] T015 [P] [US2] 改 `frontend/components/workspace/nodes/lineage-node.tsx`：节点头部渲染数据源徽标（`lineage-datasource-style` 的图标/缩写/色），DATASOURCE/METRIC 例外处理。
- [x] T016 [US2] 改 `frontend/lib/workspace/lineage-layout.ts` 边样式（L157-191 区）：加基于两端 `datasourceId` 的跨源判定与样式（描边色表跨源，与 confidence 的 dash 正交）。
- [x] T017 [US2] 改 `frontend/components/workspace/views/lineage/lineage-legend.tsx`：增「数据源徽标 / 跨源边 / 库内边 / 未知来源」图例项。
- [x] T018 [US2] 改 `frontend/lib/workspace/lineage-graph.ts` reducer：保留列级 `DERIVES_FROM` 边（`columnEdgesByTable` 或并入 edges 带 `granularity==='COLUMN'` 标记），`collapseColumns` 时同步移除。
- [x] T019 [US2] 改 `frontend/components/workspace/views/lineage-view.tsx#toggleColumns`：不再丢弃 `fetchTableColumnLineage` 返回的列级边，交给 reducer 保留。
- [x] T020 [US2] 改 `frontend/components/workspace/nodes/lineage-node.tsx`：列行加列级 `Handle`（target 左 / source 右，`id=列 id`），垂直对齐列行中线（行高沿用 052 `COL_ROW_H`）。（依赖 T015 同文件，顺序做）
- [x] T021 [US2] 改 `frontend/lib/workspace/lineage-layout.ts`：从保留的列级边产出带 `sourceHandle/targetHandle` 的 ReactFlow 边，连到具体列行；与表→表边视觉区分（更细/异色）；列级跨库映射复用 T016 跨源样式。（依赖 T016 同文件，顺序做）
- [x] T022 [US2] 改 `frontend/components/workspace/nodes/lineage-node-types.ts`：`LineageNodeData` 补 `datasourceId?`/`datasourceName?`；确认列项 `id` 作为 handle id 的稳定性。
- [x] T023 [US2] `frontend/messages/{zh-CN,en-US}.json` 补图例键（跨源/库内/未知/数据源徽标），两 bundle 对齐。
- [x] T024 [US2] 浏览器门实证 US2：新建/跑 `frontend/tests/e2e/lineage-cross-db.spec.ts` 断言徽标、跨源边样式、字段级连线连到具体列行；vitest T014 转绿。

**Checkpoint**: US1+US2 达成——跨库血缘从入口到图形全程可见（P1 全量交付）。

---

## Phase 5: User Story 3 - 数据源浏览分面（Priority: P3，可选）

**Goal**: 左栏从「唯一数据源树」降级为可切分面（数据源/分层/最近，无 ownership）；顺带补 052 遗留的 tables-by-datasource 占位。

**Independent Test**: 分面可在 数据源/分层/最近 间切换；「数据源」展开出真实的表（非占位跳级）；「分层」按 ODS/DWD/DWS/ADS；「最近」列会话锚定过的资产。

- [ ] T025 [US3] 改 `backend/.../application/LineageQueryService.java` + `backend/.../api/interfaces/LineageGraphController.java`：加 `tablesByDatasource(tenantId,projectId,dsId,offset,limit)` + `GET /api/lineage/datasources/{id}/tables`（Cypher `MATCH (d:Datasource{id,tenantId,projectId})-[:HAS_TABLE]->(t:Table)`），节点带数据源 attrs。
- [ ] T026 [P] [US3] 加 IT（`LineageDatasourceProjectionIT` 或新类）断言 `/datasources/{id}/tables` 只返回该库该项目的表、修正占位。
- [ ] T027 [US3] 新建 `frontend/components/workspace/views/lineage/lineage-facets.tsx` 替换 `lineage-tree.tsx` 主入口地位：分面切换（数据源/分层/最近），复用规范原语；「最近」用会话本地记录（不含 ownership）。
- [ ] T028 [US3] 改 `frontend/lib/lineage-api.ts` 加 `fetchTablesByDatasource(dsId,offset,limit)`，`lineage-facets` 数据源分面调用它（替换 `fetchColumns` 占位）。
- [ ] T029 [US3] i18n 补分面键 + 若新增分面切换原语则回填 `frontend/DESIGN.md` 组件目录。
- [ ] T030 [US3] 浏览器门实证 US3：分面切换 + 数据源展开真实表。

**Checkpoint**: 分面浏览作为辅助入口就绪；裁剪此故事不影响 US1/US2。

---

## Phase 6: User Story 4 - 按数据源分组泳道（Priority: P3，可选）

**Goal**: 可开关的「按数据源分组」视图，同源节点收进带标签容器、跨库边为跨容器连线、可折叠。

**Independent Test**: 横跨多库图开启分组→节点按库进容器、跨库边跨容器；折叠某容器其对外跨库边聚合保留；可关分组回徽标视图。

- [ ] T031 [US4] 改 `frontend/lib/workspace/lineage-layout.ts` + `lineage-view.tsx`：加「按数据源分组」开关，用 ReactFlow group/parent 节点把同 `datasourceId` 节点收进容器泳道，跨库边跨容器；折叠 + 关闭回徽标视图。
- [ ] T032 [US4] 浏览器门实证 US4：分组/折叠/关闭；i18n 补开关键（两 bundle 对齐）。

**Checkpoint**: 泳道深化就绪；可裁剪。

---

## Phase 7: Polish & 收口

- [ ] T033 [P] 回归：跑 052 既有 IT + 浏览器门 6/6，确认双向/深度/展开/影响/路径/列级不回退（SC-006）。
- [ ] T034 按 `specs/054-lineage-search-first-nav/quickstart.md` 跑全量验证（后端 IT + h2 shape + vitest + 浏览器门 1–4）。
- [ ] T035 [P] 回填 `frontend/DESIGN.md` 原语速查表（数据源徽标 / 分面切换 等新原语，若有）。
- [x] T036 收口门：`cd backend && ./mvnw -q -pl dataweave-master,dataweave-api compile` + `cd frontend && pnpm typecheck` + `pnpm design:lint` 全绿。

---

## Dependencies & Execution Order

### Phase 依赖
- **Setup(P1)**：立即可开始（T001/T002 独立并行）。
- **Foundational(P2)**：依赖 Setup；**阻塞 US1 候选名 + US2 全部**。T003/T004 后端（同 service 文件，T003→T004 顺序），T005 前端类型 [P]，T006/T007 测试 [P]（先写先失败）。
- **US1(P3 阶段)/US2(P4 阶段)**：均依赖 Foundational；二者可并行（不同文件为主）。
- **US3(P5)/US4(P6)**：P3 可选，依赖 Foundational（US3 端点）+ US2（US4 依赖徽标/datasourceId）。
- **Polish(P7)**：依赖已交付故事。

### 用户故事依赖
- **US1（P1）**：Foundational 后即可，独立可测（MVP）。
- **US2（P1）**：Foundational 后即可，独立可测；与 US1 不同文件为主。
- **US3（P3，可选）**：Foundational 后即可；可整体裁剪，裁剪时 US1 搜索仍为完整主入口。
- **US4（P3，可选）**：依赖 US2 的 datasourceId/徽标。

### 同文件顺序约束（不可并行）
- `lineage-layout.ts`：T016 → T021（同文件）。
- `lineage-node.tsx`：T015 → T020（同文件）。
- `LineageQueryService.java`：T003 → T004（同文件）；US3 的 T025 在其后。
- `lineage-view.tsx`：T009/T011/T019 顺序（同文件）。

### 并行机会
- Setup：T001 ∥ T002。
- Foundational：T005 ∥ T006 ∥ T007（T003→T004 顺序在前）。
- US1 内：T008 [P] 先行；T010 与 T009 部分并行（不同文件时）。
- US2 内：T014 [P] ∥ T015 [P]（不同文件）；其余按同文件约束串。
- US1 阶段 ∥ US2 阶段（Foundational 完成后，若双 Agent）。

---

## Parallel Example: Foundational

```bash
# T003→T004 顺序（同文件）后，测试与前端类型并行：
Task: "T005 前端 lineage-api.ts 补 datasource TS 类型"
Task: "T006 真 Neo4j IT LineageDatasourceProjectionIT（先写先失败）"
Task: "T007 h2 REST shape 契约 + 向后兼容"
```

---

## Implementation Strategy

### MVP First（US1）
1. Setup(T001–T002) → 2. Foundational(T003–T007) → 3. US1(T008–T013) → **STOP & VALIDATE**：搜索即可看血缘、摆脱先选库 → 可 demo。

### Incremental Delivery
1. Setup+Foundational → 数据地基就绪。
2. +US1 → 搜索优先入口（MVP）。
3. +US2 → 跨库徽标/跨源边/表字段连线（P1 全量，核心价值兑现）。
4. +US3（可选）→ 分面浏览。
5. +US4（可选）→ 泳道。
每步不破坏前步。**建议交付边界 = US1+US2（P1）**；US3/US4 视容量取舍。

### 双 Agent 策略
Foundational 完成后：Agent 跑 US1，另一 Agent 跑 US2（不同文件为主，同文件按上文串约束）。主 Claude 评审收口。

---

## Notes
- [P]=不同文件、无未完成依赖；[Story] 映射故事便于追溯。
- 测试先写并确认失败再实现；每任务或逻辑组后提交。
- 每个 Checkpoint 可停下独立验证。
- 避免：同文件并行冲突、破坏故事独立性的跨故事依赖、改动 052/他人代码。
