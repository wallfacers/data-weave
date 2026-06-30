# Specification Quality Checklist: 运行态同步行数采集（recordSynced 接入）

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-30
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details — **项目惯例注记**：本项目 spec（参照 019/024）面向实现期 AI agent，有意保留技术实体（SqlTableExtractor/ExecutionResult/WorkerReportService/neo4j）作精确锚点；WHAT/WHY 优先，与同级 spec 风格一致。
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders — **注记**：受众为实现期 AI agent 与资深后端工程师，与同级 spec 对齐。
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic（SC 结果导向：syncSummary 真实 SUM / 零回归 / 0 阻断 / 向后兼容）
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified（UPDATE/DELETE、字面量分号、新旧 worker/master 兼容、all-in-one 路径）
- [x] Scope is clearly bounded（SQL-only；Spark/Python/Shell 出；不改读侧/recordSynced 契约；024 列 catalog 独立）
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows（syncSummary 可用 P1 / 降级不阻断 P1 / 多表近似 P2）
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification（见 Content Quality 注记）

## Notes

- 三轮 brainstorm + 设计评审已定：源=执行器上报（SQL affected-rows）、scope=SQL-only（Spark 延后）、归属=per-table（A）、bytes=null、仅成功、:TaskRun 带 taskDefId。
- 设计评审 5 问题 + 3 note 全部并入：① UPDATE/DELETE scope 收 INSERT/MERGE + WARN 不静默丢；② 切分对齐措辞修正；③ 上报 JSON 优先 Jackson；④ statementMetrics null/empty 向后兼容；⑤ 单 statement 多表共享 updateCount；all-in-one 路径双覆盖；bizDate/tenant/project 取自 ti。
- 与 024 的关系：024 是定义态列 catalog（声明驱动），025 是运行态行数采集——两者只在 neo4j 血缘底座沾边，代码路径/数据源/关切完全独立，互不阻塞。
- 跨特性感知：合并序 021→022→023→024→025；025 派生自已合并的 018 lineage 底座，不被前置阻塞。
- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`（当前全 pass）。
