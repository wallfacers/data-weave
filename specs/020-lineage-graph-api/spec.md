# Feature Specification: 血缘查询、API 与企业级前端视图

**Feature Branch**: `020-lineage-graph-api`

**Created**: 2026-06-30

**Status**: Draft

**Input**: 共享设计:[docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md](../../docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md)(本 spec 为其中「C · 查询 + API + 前端」一份)

> **范围边界**:本特性只负责**读侧**——Cypher 查询取代 Java 内存 BFS、`/api/lineage/*` 多粒度重设计、企业级前端血缘视图。图的写入/存储在 018;列级解析在 019。本特性消费 018 写入图中的数据。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 多粒度血缘图浏览与下钻(Priority: P1)

用户在前端血缘视图看到 数据库→表→列 的结构层级,可逐级展开/折叠,叠加表级与列级血缘流。

**Why this priority**: 企业级血缘的核心使用场景;承载换底座后多粒度能力的价值呈现。

**Independent Test**: 在 neo4j 已有血缘数据的前提下,打开血缘视图,能看到库/表/列三级结构并展开,血缘流边正确渲染。

**Acceptance Scenarios**:

1. **Given** neo4j 含库/表/列血缘, **When** 打开血缘视图, **Then** 渲染出数据源→表→列分层图,支持展开折叠。
2. **Given** 选中一张表, **When** 切换到列粒度, **Then** 展示该表列与列级血缘流。

### User Story 2 - 任意深度上下游与影响面分析(Priority: P1)

用户对某表/某列查上游(数据从哪来)、下游(影响谁)、整体影响面,深度不限。

**Why this priority**: 换 neo4j 的核心收益——Cypher 变长路径取代内存 BFS,支撑任意深度影响分析。

**Independent Test**: 对一条多跳血缘链路,`GET /api/lineage/tables/{id}/downstream?depth=N` 与 `impact/{nodeId}` 返回正确的可达集合。

**Acceptance Scenarios**:

1. **Given** `a→b→c→d` 的表级链路, **When** 查 a 的 downstream(depth 不限), **Then** 返回 {b,c,d}。
2. **Given** 列级链路, **When** 查某列 upstream, **Then** 返回其列级源链路。
3. **Given** 某表, **When** 查 impact, **Then** 返回所有经 `FLOWS_TO|DERIVES_FROM` 可达的下游(表+列)。

### User Story 3 - 指标血缘与运行态叠加(Priority: P2)

用户查某指标的血缘(它由哪些表/列计算)、并在血缘图上叠加今日同步行数等运行态信息。

**Why this priority**: 指标血缘 + 运行态是企业级治理常用面,但优先级低于结构浏览与影响面。

**Independent Test**: `GET /api/lineage/metrics/{id}/lineage` 返回指标→表/列;`sync-summary` 返回今日同步行数。

**Acceptance Scenarios**:

1. **Given** 一个派生指标, **When** 查其 lineage, **Then** 返回其 COMPUTED_FROM 的表/列。
2. **Given** 有运行态 SYNCED 数据, **When** 查 sync-summary, **Then** 返回当日同步行数(无数据返回 null,前端显示估算中)。

### Edge Cases

- neo4j 不可达 → 血缘 API 返回明确错误码 `lineage.store_unavailable`,前端友好降级,平台其余功能不受影响。
- 超大图(深链路/广扇出)→ 查询 MUST 有深度/节点数上界与分页,避免一次性拉爆。
- 环路血缘(理论上不应有,但需防御)→ 变长路径查询 MUST 防无限环。
- 列粒度数据缺失(019 未覆盖的降级表)→ 列视图优雅显示"仅表级"。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 用 Cypher 变长路径(`[:FLOWS_TO|DERIVES_FROM*]`)实现上游/下游/邻域/影响面查询,取代现 Java 内存 BFS。
- **FR-002**: 系统 MUST 重设计 `/api/lineage/*` 为多粒度:`datasources`、`tables/{id}/columns`、`tables/{id}/{upstream|downstream}?depth=&granularity=`、`columns/{id}/{upstream|downstream}`、`impact/{nodeId}`、`metrics/{id}/lineage`、`sync-summary`。
- **FR-003**: 查询 MUST 按 `tenantId/projectId` 隔离(沿用 MCP 租户隔离)。
- **FR-004**: 影响面/路径查询 MUST 有深度上界、节点数上界与分页/截断,且 MUST `log` 截断(不静默丢)。
- **FR-005**: neo4j 不可达时 API MUST 返回稳定错误码 `lineage.store_unavailable`(走 BizException + GlobalExceptionHandler),前端 MUST 友好降级。
- **FR-006**: 前端 MUST 提供企业级血缘视图:库/表/列三级展开折叠、表级+列级血缘流、影响面高亮、指标叠加;遵循 DESIGN.md 与前端栈约定(shadcn base / hugeicons / 语义 token)。
- **FR-007**: 静态 UI 文案 MUST 走 next-intl(zh-CN/en-US 双语 key 等集);错误走 BizException;数据术语(lineage/DAG/cron)保留英文。
- **FR-008**: API 返回结构 MUST 携带粒度与节点类型(datasource/table/column/metric),供前端分层渲染。

### Key Entities *(include if feature involves data)*

- **GraphNode(返回视图)**:`id, type(datasource|table|column|metric), name, layer, ...`。
- **FlowEdge(返回视图)**:`from, to, granularity(table|column), taskDefId, confidence, transform`。
- **ImpactResult**:某节点的全下游可达集合(分层/分粒度)。
- **MetricLineage**:指标→表/列。
- **SyncSummary**:今日同步行数(可空)。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 任意深度上下游/影响面查询返回正确可达集合,与 018 写入的血缘一致(经 Testcontainers 集成测试验证)。
- **SC-002**: 血缘视图能渲染库/表/列三级并下钻,列级血缘流可视。
- **SC-003**: neo4j 不可达时,血缘 API 返回 `lineage.store_unavailable`,平台其余页面/功能 100% 不受影响。
- **SC-004**: 超大图查询有界(深度/节点上界 + 分页),无一次性拉爆;截断有日志。
- **SC-005**: 前端 `pnpm typecheck` 零错;双语 key 等集(CI 校验);血缘视图浏览器验证渲染正确。

## Assumptions

- 图数据由 `018-lineage-neo4j-store` 写入;列级数据由 `019` 经 018 写入;本特性只读。
- 查询能力以共享设计 §7 的端点清单为基线,可在企业级需要时扩展,但 MUST 保持租户隔离与有界查询。
- 前端遵循现有 Workspace 多 tab 架构与 DESIGN.md;血缘视图作为一个 view 注册进 registry。
- neo4j 访问层(driver/@Bean)由 018 提供,本特性复用其只读会话。
