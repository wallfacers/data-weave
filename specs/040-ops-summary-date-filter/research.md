# Research: 调度与运行态总览日期筛选

**Feature**: 040-ops-summary-date-filter | **Date**: 2026-07-02 | **Spec**: [spec.md](spec.md)

> Phase 0 产出。本 feature 极简——单 API 加参数 + 单组件加 DatePicker——无 NEEDS CLARIFICATION。

## D1: 日期字段选择

**Decision**: 按 `task_instance.biz_date` 过滤。

**Rationale**: 
- `biz_date` 是任务实例的业务日期，运维心智是"某天业务数据跑得怎么样"，而非"某天创建/执行的任务"。
- 下方实例表格（`PeriodicInstancesPanel`）已使用 `bizDate` 筛选，顶条统计与表格筛选口径一致。
- `biz_date` 已有索引 `idx_task_instance_node_bizdate`，查询性能无虞。

**Alternatives**: `created_at`——否决，与运维心智不符；`started_at`/`finished_at`——否决，运行时间跨度不定，单天过滤无意义。

## D2: Repository 方法扩展

**Decision**: 新增 `findByProjectIdAndRunModeAndBizDate(Long projectId, String runMode, String bizDate)` 到 `TaskInstanceRepository`。

**Rationale**: Spring Data JDBC 方法命名约定自动生成查询，零 SQL 手写。与既有 `findByProjectIdAndRunMode` 一脉相承。

**Alternatives**: JdbcTemplate 手写 SQL——否决，Repository 衍生方法已覆盖此简单查询，手写无收益。

## D3: OpsService 方法签名

**Decision**: `summary(Long projectId)` → `summary(Long projectId, String bizDate)`；`instances(Long projectId)` 内部按 `bizDate` 分流。

**Rationale**: 最小改动——`bizDate` 非空时调新 Repository 方法，空时走原逻辑。向后兼容。

**Alternatives**: 新建独立方法 `summaryByBizDate`——否决，增加方法数不增加清晰度。

## D4: API 契约

**Decision**: `GET /api/ops/summary` 增可选 `bizDate` 参数（`required=false`，格式 `yyyy-MM-dd`）。

**Rationale**: 可选参数保证向后兼容——不传时行为与当前完全一致。

**Alternatives**: 新端点 `/api/ops/summary/daily`——否决，功能同一端点，参数化更简洁。

## D5: SLA 风险端点不改

**Decision**: `GET /api/ops/eta-summary` 不变，前端始终不传 `bizDate`。

**Rationale**: SLA 风险反映当前全局时效风险，天然不受日期筛选约束（spec FR-003）。代码上 `eta-summary` 端点不接受也不使用 `bizDate`。

**Alternatives**: 加参数但前端不传——否决，未用参数是 dead code。

## D6: DatePicker 选用

**Decision**: 复用项目已有 `components/ui/date-picker.tsx`。

**Rationale**: 已有组件，零新建；单日选择模式直接满足"精准某天"需求。

**Alternatives**: 新建 DatePicker——否决，轮子已存在。

## D7: i18n 标签修改

**Decision**: `topTotal` 值从 "今日总数"→"总数" / "Today Total"→"Total"，两 bundle 同步。

**Rationale**: 去掉"今日"消除与 DatePicker 的语义冲突。其他 4 个 key 无时间语义，不动。

**Alternatives**: 动态标签随日期变化——否决，增加复杂度而无实际收益。
