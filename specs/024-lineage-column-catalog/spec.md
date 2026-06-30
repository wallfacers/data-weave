# Feature Specification: 声明驱动的列血缘 Catalog

**Feature Branch**: `024-lineage-column-catalog`

**Created**: 2026-06-30

**Status**: Draft

**Input**: 后续待办（lineage L2 收口遗留）：L1 真实列 catalog 鸡生蛋未解——019 产 `ColumnEdge` 靠 catalog、018 写 `:Column` 又靠 `ColumnEdge`，接入点 `EmptyColumnLineageCatalog` 已留好但恒返回空。真实列元数据来源 = 任务列声明（`.task.yaml`）；运行时 schema 反射已被 `019 research.md` D2 否决。

> **范围边界**：本特性交付「声明驱动的列血缘闭环」——在 `.task.yaml` 声明列 schema 与期望列边，驱动 neo4j **独立 seed `:Column`/`:DERIVES_FROM`** 破解 019↔018 的鸡生蛋，并**激活 019 FR-006 已写但零调用的列级交叉校验**（cross-check）。本特性不重写 019 解析内核、不改 020 读侧契约；它补齐的是「列元数据从哪来」与「声明如何对账」两件 019 假设存在但未交付的事。运行态行数（recordSynced）是另一个独立 feature，不在本期。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 声明 schema 让列血缘真正流出（破鸡生蛋）(Priority: P1)

Agent 在 `.task.yaml` 声明 SQL 涉及表的列（名+类型），push 后这些列被**独立 seed** 进 neo4j `:Column`（不经 ColumnEdge）。此后 019 解析该任务 SQL 时，`ColumnLineageCatalog` 能从 neo4j 读到列元数据，Calcite 得以注册 schema、展开目标列、溯源，产出 CONFIRMED 列边——鸡生蛋闭环打通。

**Why this priority**: 这是整个列血缘体系能产生真实（非启发式）列边的前提；没有它，019 永远拿不到 catalog、列血缘事实为空（当前现状）。

**Independent Test**: 给定一个声明了 `schema` 的任务，push 后断言 neo4j 出现对应 `:Column`（带 type/ordinal）；再跑 019 extract，断言产出 CONFIRMED 列边（而非全 UNVERIFIED 启发式）。

**Acceptance Scenarios**:

1. **Given** Agent 在 `.task.yaml` 声明 `schema: { orders: [{name: order_id, type: BIGINT}, ...] }`, **When** push, **Then** neo4j 出现 `(:Table)-[:HAS_COLUMN]->(:Column{name:order_id,dataType:BIGINT,ordinal:0})`。
2. **Given** 该任务 SQL 为 `INSERT INTO orders_clean(total) SELECT amount FROM orders` 且 schema 已**先于 extract** seed 进 neo4j（FR-009 要求 plan 调整调用序保证）, **When** extract, **Then** 产出 `orders_clean.total ← orders.amount`、confidence=CONFIRMED。
3. **Given** 同一任务**不声明** schema, **When** push + extract, **Then** 维持现状（表级 + UNVERIFIED 启发式），零回归。

---

### User Story 2 - 声明期望列边 + 冲突检测（cross-check 激活）(Priority: P1)

Agent 在 `.task.yaml` 声明期望的列级派生（`columnLineage: [{from: orders.amount, to: orders_clean.total}]`）。系统把它与 019 从 SQL 推导出的列边对账：一致→CONFIRMED 加固；只声明未推导→DECLARED；声明与推导矛盾→CONFLICT。CONFLICT 作为数据质量信号写边并透出，**不阻断 push**。

**Why this priority**: cross-check 是 019 FR-006 已承诺但从未挂线的能力（`extractAndCrossCheck`/`ColumnLineageCrossCheck` 已写、零调用）；它把列血缘从"被动解析"升级为"可断言、可校验"，CONFLICT 能抓出 SQL 与意图不符的真实 bug。

**Independent Test**: 给定声明边集 D 与 SQL 推导边集 R 的四种关系（D∩R / D\R / R\D / 冲突），断言对账输出四种 confidence 正确，且冲突边被写入、push 不被拦。

**Acceptance Scenarios**:

1. **Given** 声明 `A→B` 且 SQL 推导也得 `A→B`, **When** 对账, **Then** 该边 confidence=CONFIRMED。
2. **Given** 声明 `A→B` 但 SQL 推导得 `A→C`, **When** 对账, **Then** 产出 CONFLICT 信号（边写入、标 CONFLICT），push 仍成功。
3. **Given** 声明 `A→B` 但 SQL 解析整体降级（R 为空）, **When** 对账, **Then** `A→B` 仍以 confidence=DECLARED 写入（声明兜底建图）。
4. **Given** 只声明 schema、不声明 columnLineage, **When** 对账, **Then** 仅走 019 现有 confidence，无 DECLARED/CONFLICT（US1 不依赖 US2）。

---

### User Story 3 - 声明兜底建图（SQL 解析失败也有列血缘）(Priority: P2)

即使 019 对某条 SQL 完全解析失败（DDL/动态/方言不支持），只要 Agent 声明了 columnLineage，期望列边仍以 DECLARED 置信度写入 neo4j——列血缘视图不会因解析失败而一片空白。

**Why this priority**: 兜底保证列血缘的可用性下限；对 019 暂不支持但 Agent 已知血缘的 SQL 形态尤其有价值。

**Independent Test**: 给定一条 019 unparsed 的 SQL + 声明 columnLineage，断言声明边以 DECLARED 写入、`:Column` 由 schema seed。

**Acceptance Scenarios**:

1. **Given** SQL 为 019 返回 unparsed 的 DDL/动态 SQL, **When** 该任务声明了 columnLineage, **Then** 声明边以 confidence=DECLARED 落 neo4j。
2. **Given** 同上但**未声明** columnLineage, **Then** 无列边写入（与现状一致）。

---

### Edge Cases

- 声明格式非法（坏 yaml / 未知类型 / 畸形边引用）→ 跳过该声明 + 日志，不阻断 push。
- 声明的表名 key 与 SQL 写法不一致（大小写/schema 前缀）→ catalog 匹配不上 → 该表列级降级，可告警，不崩。
- 多任务对同一表声明了不一致的类型 → seed 覆盖语义（latest-wins）；漂移检测出范围（记为后续债）。
- 完全不声明 → 现状表级行为，零回归。
- catalog 查 neo4j 失败 → `lookupTable` 返回 empty（契约：永不抛）→ 退表级。
- 跨任务首见表：upstream 任务未 push seed 前，当前任务对该表的列级降级（019 已接受 edge case）。
- 首次 push 排序：若 seed 未提至 extract 之前（当前代码逆序），当前任务自身声明的表当次降级、仅后续 push/任务解析（019「首见表降级」兜底；FR-009 要求 plan 修正调用序以避免）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001（声明表面）**: 系统 MUST 在 `.task.yaml` 支持两块**可选**声明：`schema`（表名 → 有序列 `{name, type}`）与 `columnLineage`（`{from: 表.列, to: 表.列}` 边列表）。两块独立，可只给其一或都不给。
- **FR-002（文件优先 + round-trip）**: 声明 MUST 作为任务定义文件（`.task.yaml`）的明文一部分，经 `pull`/`push` round-trip 不丢失字段（Constitution I 文件优先 + II round-trip integrity）。
- **FR-003（破循环 seed）**: `recordTaskIo`（生产调用点：`createAndOnline` + `push`；`publish` **不**写血缘、只写版本快照）MUST 在写入时把声明的 `schema` **独立 MERGE** 成 neo4j `:Column`（带 `dataType`/`ordinal`，经已有幂等的 `ensureColumn`——其 type/ordinal 参数已就绪、当前调用点传 `null` 待改），**不依赖** 019 的 ColumnEdge。这是破解鸡生蛋的核心。声明仅在 **push（项目同步）路径**经 `.task.yaml` 注入；`createAndOnline`（MCP 参数路径）不经 FileContract，本期不强制支持声明（见 research.md R2）。
- **FR-004（catalog 读侧）**: 系统 MUST 提供 `Neo4jColumnLineageCatalog implements ColumnLineageCatalog`，从 neo4j `(:Table)-[:HAS_COLUMN]->(:Column)` 有序回组 `TableSchema`；在 neo4j 环境生效、替换 `EmptyColumnLineageCatalog`（装配机制 `@ConditionalOnProperty` 与 H2 fallback 见 research.md R5，非裸 `@Primary`）；查询失败 MUST 返回 empty、永不抛异常（沿用既有契约）；查询 MUST 按租户+项目隔离——`lookupTable` 签名加 `(tenantId, projectId)`（research.md R3，不用 TenantContext）。
- **FR-005（cross-check 激活）**: 系统 MUST 激活已就绪但零调用的 `extractAndCrossCheck(sql, catalog, declaredEdges)` → `ColumnLineageCrossCheck.crossValidate`，对声明边集 D 与 SQL 推导边集 R 对账，产出每条边的 confidence。
- **FR-006（置信度语义）**: 对账 confidence：D∩R=CONFIRMED；D\R=DECLARED；R\D 沿用 019 现有值；声明与推导映射矛盾=CONFLICT。`confidence` 枚举 MUST 沿用 019 FR-004 的 `{CONFIRMED, UNVERIFIED, CONFLICT}` 并**新增 `DECLARED`**（不重定义既有值）。
- **FR-007（落边）**: `recordTaskIo` MUST 把对账后的边集（含 DECLARED 兜底边）MERGE 成 `:DERIVES_FROM` 并带 confidence 属性；DECLARED 边即使 SQL 解析失败也 MUST 写入。
- **FR-008（CONFLICT 不阻断）**: CONFLICT MUST 作为边的 confidence 属性写入并在读侧透出，MUST NOT 阻断 push（与"解析是增强、绝不阻断"原则一致）。
- **FR-009（排序不变量）**: seed `:Column`（FR-003）MUST 在 019 extract 之前完成，使当前任务声明的表在当次 push 内即可被解析；跨任务首见表降级为可接受。**实现注记**：当前 `TaskService` 是 extract→recordTaskIo 逆序（extract 在 485、seed 在 494），plan MUST 把声明 schema 的 seed 提至 extract 调用之前（拆独立 seed 步骤或调整 recordTaskIo 调用序），否则当前任务自身表当次降级、仅后续 push/任务可解析（019「首见表降级」语义兜底）。
- **FR-010（降级与零回归）**: 任何声明解析/seed/catalog 失败 MUST 降级（跳过+日志或退表级），MUST NOT 抛出阻断主链路的异常；无声明任务 MUST 保持与现状完全一致的表级行为。
- **FR-011（内核复用）**: 实现 MUST 复用 `ensureColumn`、`ColumnLineageCrossCheck`、`recordTaskIo`、Calcite 解析器与 020 读侧契约，MUST NOT 重写 019 解析内核或 020 查询契约（Constitution V 内核复用）。

### Key Entities *(include if feature involves data)*

- **ColumnSchema 声明**（`.task.yaml` `schema` 块）: 表限定名 → 有序列 `{name, type}`。seed `:Column` 的来源。
- **Declared ColumnEdge**（`.task.yaml` `columnLineage` 块）: `{from: 表.列, to: 表.列}`。cross-check 的 D 集，兼 DECLARED 兜底建图来源。
- **`:Column` 节点**: neo4j 节点 `{columnKey, name, dataType, ordinal}`；由 FR-003 独立 seed（不再仅是 Edge 的副产品）。
- **`:DERIVES_FROM` 边**: 列级派生边，带 `confidence`。
- **Confidence**: CONFIRMED（声明∩推导）/ UNVERIFIED（019 启发式降级，沿用）/ CONFLICT（声明与推导矛盾）/ DECLARED（仅声明、推导未印证，024 新增）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 声明了 `schema` 的任务，push 后 neo4j 出现对应 `:Column`（带 type/ordinal），且 019 对其 SQL 产出 CONFIRMED 列边（而非空/纯启发式）——鸡生蛋闭环可证。
- **SC-002**: cross-check 四种对账情形（D∩R / D\R / R\D / 冲突）confidence 全部正确；CONFLICT 边被写入且 push 不被拦。
- **SC-003**: SQL 解析失败时，声明边以 DECLARED 写入（兜底建图可用性下限成立）。
- **SC-004**: 无声明任务行为与现状 100% 一致（零回归）。
- **SC-005**: 声明经 push→pull round-trip 无字段丢失。
- **SC-006**: 任何声明/catalog 异常均干净降级，0 抛出阻断主链路的异常。

## Assumptions

- 列元数据**唯一来源 = 任务列声明**（`.task.yaml`）；运行时 schema 反射出范围（019 research D2 已否决）。若将来声明不足需反射，另开 feature。
- 019 解析器（Calcite 三段式 + 降级）与 020 读侧契约（`/api/lineage/columns/*`）已就绪且**本特性不改**；本特性激活 019 FR-006 已写未挂的 cross-check，并补 019 假设存在但缺失的 catalog 实现。
- `:Column`/`:HAS_COLUMN`/`:DERIVES_FROM` 图模型由 018 定义并就绪；本特性仅补「独立 seed `:Column`」的调用路径（`ensureColumn` 能力已存在，当前无调用方）。
- 仅 SQL 类型任务受益于列解析；`columnLineage` 声明对任何任务类型均可作为 DECLARED 兜底边写入。
- 多任务对同表声明漂移的检测出范围（记为后续债）；本期 seed 语义为 latest-wins。
- 实现期开 `dw-024-lineage-column-catalog` worktree（与 021/022/023 惯例一致），合并序排在 021→022→023 之后。
- **plan 期需落实的风险点**（非 spec 级，记录备查）：
  - `.task.yaml` 已有 `formatVersion: 1`，新增可选 `schema`/`columnLineage` 块需确认是否 bump 版本或按前向兼容可选键处理。
  - `ColumnLineageCatalog.lookupTable(name)` 无 tenant 参数；需确认 `TenantContext` 在 `TaskService`/`ProjectSyncService` 的 extract 调用点已 set，否则按租户隔离收口的机制需补（走 `TenantContext` 或 qualifiedName 编码）。
  - DECLARED 边写入：`recordTaskIo` 入参需同时收 declared edges（不止 019 的 `ColumnEdge`），对账后的 union 边集统一落 `:DERIVES_FROM`。
  - H2 / 无 neo4j profile：`EmptyColumnLineageCatalog` 作为非 neo4j profile 的 fallback 保留，`@Primary`（FR-004）只在 neo4j profile 生效，避免 H2 本地启动崩。
