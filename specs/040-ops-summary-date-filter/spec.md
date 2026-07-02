# Feature Specification: 调度与运行态总览日期筛选

**Feature Branch**: `040-ops-summary-date-filter`

**Created**: 2026-07-02

**Status**: Draft

**Input**: User description: "调度与运行态总览下的几个统计目录需要支持日期筛选，并且里面的统计项，明确下，是任务流实例还是任务实例"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 按业务日期查看任务实例统计 (Priority: P1)

运维打开运维中心，顶条「调度与运行态总览」默认展示今天的任务实例统计数据（总数/运行中/成功/失败）。运维可以切换日期，查看任意一天的任务实例运行情况。

**Why this priority**: 这是本 feature 的唯一核心功能——让统计项支持日期筛选。当前统计的是全部历史数据，无法按天查看，运维无法快速了解"今天跑得怎么样"。

**Independent Test**: 顶条出现日期选择器，默认今天；切换日期后 4 个统计数字随之变化。

**Acceptance Scenarios**:

1. **Given** 运维打开运维中心，**When** 页面加载，**Then** 顶条日期选择器默认选中今天，4 个统计项（总数/运行中/成功/失败）显示今天 `biz_date` 下的任务实例数据。
2. **Given** 顶条已显示今天的统计，**When** 运维将日期切换为 3 天前，**Then** 4 个统计数字刷新为该日期的数据。
3. **Given** 运维切换到没有任务实例的日期，**When** 数据加载完成，**Then** 4 个统计项均显示 0，不报错。

---

### User Story 2 - SLA 风险不受日期筛选影响 (Priority: P2)

SLA 风险统计保持全局视角，不受日期筛选影响，运维切换日期时 SLA 风险数字不变。

**Why this priority**: SLA 风险反映的是当前全局的时效风险，不应被日期筛选收缩范围。这是与 P1 配套的边界约束。

**Independent Test**: 切换日期后，总数/运行中/成功/失败变化，SLA 风险不变。

**Acceptance Scenarios**:

1. **Given** 顶条显示某日期的统计，**When** 运维切换日期，**Then** 总数/运行中/成功/失败随之刷新，SLA 风险保持不变。

---

### User Story 3 - 标签语义与日期筛选一致 (Priority: P3)

总数标签不再写"今日总数"，改为"总数"，避免与日期选择器的当前选中日期产生歧义。

**Why this priority**: 纯文案修正，不涉及逻辑改动。但消除"今日"与任意日期之间的语义冲突，是交互一致性的基础。

**Independent Test**: 标签显示"总数"而非"今日总数"。

**Acceptance Scenarios**:

1. **Given** 运维打开运维中心，**When** 页面渲染顶条，**Then** 第一个统计项标签为"总数"（中文）/ "Total"（英文），不含"今日"字样。

---

### Edge Cases

- 日期选择器选中无数据的日期 → 4 个统计项全显示 0，不报错、不显示异常状态。
- 后端 `/api/ops/summary` 不传 `bizDate` 参数 → 行为与当前一致（全量统计），保持向后兼容。
- `bizDate` 传入非法格式 → 后端返回错误（参数校验），前端不传非法值（DatePicker 约束格式）。
- 网络异常导致接口失败 → 统计项显示 fallback 值 0（与现有 graceful fallback 行为一致）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 运维中心顶条 MUST 提供日期选择器，支持选择单个业务日期（`biz_date`），默认选中今天。
- **FR-002**: 选中日期后，总数、运行中、成功、失败 4 个统计项 MUST 按该 `biz_date` 过滤任务实例（TaskInstance，`run_mode = NORMAL`）。
- **FR-003**: SLA 风险统计项 MUST NOT 受日期筛选影响——始终保持全局视角，不传 `bizDate` 参数。
- **FR-004**: 后端 `GET /api/ops/summary` MUST 新增可选参数 `bizDate`（格式 `yyyy-MM-dd`）；不传时行为不变，向后兼容。
- **FR-005**: 总数统计项标签 MUST 从"今日总数"改为"总数"（中文）/ "Total"（英文），消除与日期选择器的语义冲突。
- **FR-006**: 日期筛选后的 `failedInstances` 列表 MUST 同样受 `bizDate` 过滤，与统计数字口径一致。

### Key Entities

- **TaskInstance（任务实例）**: 本 feature 统计的唯一实体。筛选依据 `biz_date`（业务日期）字段，仅统计 `run_mode = NORMAL` 的实例。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 运维可在 3 秒内切换日期并看到对应日期的任务实例统计数据。
- **SC-002**: 切换日期后，4 个统计项数值与该日期 `biz_date` 下的实际任务实例数一致（可通过对数据库直接查询验证）。
- **SC-003**: 选中无数据日期时，统计项显示 0 而非报错或空白。
- **SC-004**: 原有不传 `bizDate` 的调用方（如有）行为不受影响，统计结果与改动前一致。

## Assumptions

- `task_instance.biz_date` 字段已存在且有索引（`idx_task_instance_node_bizdate`），无需改 schema。
- 日期筛选使用 `biz_date`（业务日期）而非 `created_at`（创建时间），因为运维心智是"某天业务数据跑得怎么样"而非"某天创建的任务实例"。
- 前端已有 `DatePicker` 组件（`components/ui/date-picker.tsx`），可直接复用。
- 顶条布局有足够空间容纳 DatePicker（放在副标题与刷新控件之间）。
- `run_mode = NORMAL` 的过滤逻辑不变，日期筛选是在此基础上的增量条件。
