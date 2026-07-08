# Phase 0 Research: 数据开发 LSP 创作上下文服务

关键技术决策（Decision / Rationale / Alternatives）。所有 spec 内 NEEDS CLARIFICATION 已在 `/speckit-clarify` 消解；本文记录 plan 级设计取舍。

## D1. 编排复用而非新建血缘逻辑

- **Decision**: `AuthoringContextService` 为**编排型**服务，装配既有能力：`LineageQueryService.upstream/downstream/neighborhood/columnUpstream/expandColumns/impact/downstreamTaskLevels`（neo4j 图查已齐）、`ScriptLineageService.extract`+Calcite（抽取）、`CatalogGroundingService.ground`（存在真伪）、`WorkflowEdgeRepository`（声明 DAG）、`TaskDefRepository`/目录（复用候选）。
- **Rationale**: 宪法 V「内核复用而非重写」；上述 API 已存在并经测试，新增仅编排与契约整形，风险与面最小。
- **Alternatives**: 新建独立血缘查询层——否决（重复既有 neo4j 读、违反 V）。

## D2. 遍历深度：调用方自决，广度加护栏

- **Decision**: authoring 路径的**深度**由调用方（AI agent）作为参数传入、默认多跳（如默认 3、上不设硬顶或设一个高上限如 10）；`LineageQueryService.clampDepth` 现有硬顶**仅在通用图查路径保留**，authoring 路径改用更宽的深度上限。**广度**仍经 `clampLimit` + 邻域按跳截断，且**显式标注已截断**。
- **Rationale**: clarify 决议「大力出奇迹、AI 自决深度」；深度放开满足长链路诉求，广度护栏 + 截断标注保住 SC-002 可用延迟与「绝不静默丢失」。
- **Alternatives**: 固定 1 跳（否决，违背 clarify）；深度也硬顶（否决，压制长链路场景）。
- **Open（tasks 级）**: 默认深度具体值与深度上限值，在实现期依 SC-002 实测标定。

## D3. 草稿抽取复用既有 extractor（硬不变量）

- **Decision**: 工作副本草稿的读写表/列抽取只调 `ScriptLineageService`（脚本）+ `SqlTableExtractor`（Calcite，SQL）；`DraftLineage` 是这些 extractor 输出的归一壳，**不实现第二套抽取**。
- **Rationale**: 宪法 III/V 派生——「不 fork 第二套引擎」；保证草稿抽取与 push 后抽取**零语义漂移**（等价性 SC/AC1-2）。
- **Alternatives**: CLI 侧本地 Go 抽取（否决，会与服务端抽取漂移）。

## D4. MCP `query_lineage` 漂移处理：新增并存，不破坏

- **Decision**: **保留**现有 `query_lineage`（指标→SQL→物理表，兼容既有调用方），**新增**表/列级只读工具（`query_authoring_context` / `query_task_deps` / `query_reuse_candidates` / `query_lineage_diagnostics`）承载新面；不改旧工具签名。
- **Rationale**: 最小破坏；旧工具仍有指标血缘语义，直接改签名有回归风险。FR-015 的"修漂移"由新增覆盖表/列面达成，而非删旧。
- **Alternatives**: 直接升级 `query_lineage` 返回结构（否决，破坏既有调用方）。

## D5. 复用候选判定与打分（确定性）

- **Decision**: 候选 = 已有定义中**写表目标**（产出表/列）与当前草稿写表目标重叠者；打分 = 表/列重叠度（Jaccard 类）为主 + 名称相似度（如归一编辑距离/token 重合）加权；名称相似**不单独**触发候选。阈值默认保守（仅高重叠入选），可调。
- **Rationale**: clarify 决议「写表目标重叠为主」；纯确定性、可复现、可单测（SC-003/SC-005）。
- **Alternatives**: 语义/内容相似（否决，需重分析、非确定、成本高）；仅完全同名（否决，漏报错名同义表）。

## D6. 诊断严重度分级（建议性）

- **Decision**: 三级——`ERROR`（列契约破坏、缺依赖）/`WARN`（僵依赖、悬空上游）/`INFO`（重复定义=复用提示）；全部**建议性**，绝不阻断 push。
- **Rationale**: 给 agent/开发者可排序信号；不与 push 策略闸门耦合（宪法 II，push 独立裁决）。
- **Alternatives**: 二值 pass/fail（否决，信息量不足）；可阻断（否决，违反 FR-012）。

## D7. 工作副本输入契约（无状态）

- **Decision**: CLI 收集本地工作副本中的相关草稿（内容 + 类型 + 绑定数据源元数据），一次性 `POST /api/authoring-context/analyze`；服务端**无状态**处理、零持久化、不改图谱。草稿间跨任务依赖先在提交的草稿集内解析，再回退服务端已 push 图谱；同名任务草稿覆盖已 push 版本。
- **Rationale**: clarify 决议「整个本地工作副本」+ FR-004/FR-019；无状态保证不污染治理真相（宪法 II）。
- **Alternatives**: 单文件（否决，看不到未 push 跨任务新依赖）；服务端缓存草稿（否决，引入状态与失效复杂度）。

## D8. 隔离与安全

- **Decision**: 所有查询贯穿 `TenantContext` 租户 + 项目隔离（沿用既有 036 机制）；无写、无新表、无凭据处理（数据源连接复用既有加密解析）。
- **Rationale**: 宪法 II「每次操作受隔离约束」；本特性纯读，攻击面小。
- **Alternatives**: 无。
