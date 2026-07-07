# Feature Specification: 实例列表排序 + 操作按钮状态化

**Feature Branch**: `056-instance-list-sort-actions`

**Created**: 2026-07-08

**Status**: Draft

## Clarifications

### Session 2026-07-08

- Q: WAITING/PAUSED 状态的实例操作按钮行为？ → A: 等同于 NOT_RUN——停止可用、重跑禁用、从失败恢复禁用
- Q: 排序参数跨导航保持范围？ → A: 完全持久化到 URL 参数 + localStorage，跨会话保持
- Q: PREEMPTED 状态的恢复按钮是否可用？ → A: 等同于 FAILED——允许重跑全部、从失败恢复、停止

**Input**: "任务流实例页面和任务实例页面需要支持列排序和操作按钮按状态禁用：列按时间字段排序（scheduledFireTime 倒序默认），操作按钮（重跑/恢复/停止）基于实例状态决定可用性"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 按时间列排序查看最近的实例 (Priority: P1)

运维人员在运维中心查看任务流实例和任务实例列表时，默认按计划触发时间（scheduledFireTime）倒序排列，最新的实例排在最前面，以便快速定位最近运行的实例。运维人员也可以点击其他时间列（bizDate、startedAt、finishedAt、durationMs）的列头切换排序，支持升序/降序切换。

**Why this priority**: 排序是数据浏览的基础交互。当前列表没有明确的默认排序，实例按数据库插入顺序返回，运维人员查找最新实例需要手动翻页或过滤，效率低下。这是本特性最基础的用户价值，独立交付即可提升日常使用体验。

**Independent Test**: 打开任务流实例列表，验证列表默认按计划触发时间倒序排列。点击 "Started At" 列头第一次切换为升序，第二次恢复降序。切换到任务实例 Tab 重复验证。

**Acceptance Scenarios**:

1. **Given** 运维中心任务流实例 Tab 已打开，且数据包含多个不同日期的实例，**When** 页面首次加载，**Then** 列表按 scheduledFireTime 降序排列，最新的实例出现在第一行。
2. **Given** 任务流实例列表已加载，**When** 运维人员点击 "Biz Date" 列头，**Then** 列表按 bizDate 升序排列，列头显示升序指示箭头。
3. **Given** 列表已按 bizDate 升序排列，**When** 运维人员再次点击 "Biz Date" 列头，**Then** 排序切换为 bizDate 降序。
4. **Given** 运维人员已设置 bizDate 降序排列，**When** 页面自动刷新触发重新加载，**Then** 排序保持 bizDate 降序不变。
5. **Given** 任务实例列表已加载，**When** 运维人员点击 "Started At" 列头，**Then** 列表按 startedAt 升序排列。
6. **Given** 任务流实例列表中存在手动触发（scheduledFireTime 为 null）的实例，**When** 按 scheduledFireTime 降序排列，**Then** null 值的行排在最末。

---

### User Story 2 - 操作按钮基于实例状态合理禁用 (Priority: P2)

运维人员在实例列表中对单行或批量执行操作（重跑、停止、从失败恢复）时，按钮状态应反映该操作在当前实例状态下是否合法。例如，一个正在运行中的实例不应显示可用的"重跑"按钮，但应保留"停止"按钮。这防止运维人员误操作导致重复执行或状态冲突。

**Why this priority**: 防止误操作是运维安全的基本要求。当前操作按钮无论实例状态均可点击，后端虽有校验但前端没有防护，用户点击后才报错体验差。此改进独立于排序，可单独交付。

**Independent Test**: 查看一个 RUNNING 状态的任务流实例行，验证"重跑全部"按钮为禁用状态（灰色不可点击），"停止"按钮正常可用。查看一个 FAILED 状态的实例行，验证所有操作按钮均可用。切换到任务实例列表重复验证。

**Acceptance Scenarios**:

1. **Given** 任务流实例列表中存在一条 state=RUNNING 的实例，**When** 查看该行的操作列，**Then** "重跑全部"和"从失败恢复"按钮为禁用状态，"停止"和"查看 DAG"按钮正常可用。
2. **Given** 任务流实例中存在 state=SUCCESS 的实例，**When** 查看该行的操作列，**Then** "停止"按钮为禁用状态，"重跑全部"按钮正常可用。
3. **Given** 任务流实例中存在 state=FAILED 的实例，**When** 查看该行的操作列，**Then** "重跑全部"、"从失败恢复"、"停止"均正常可用。
4. **Given** 任务流实例中存在 state=STOPPED 的实例，**When** 查看该行的操作列，**Then** "停止"按钮为禁用状态，"重跑全部"按钮正常可用。
5. **Given** 运维人员在任务实例列表中选中 3 条实例（2 条 FAILED + 1 条 RUNNING），**When** 查看批量操作栏，**Then** "Rerun" 按钮在存在 RUNNING 选中项时为禁用状态，Tooltip 提示"所选实例包含不可重跑的状态"。
6. **Given** 运维人员选中 2 条均为 FAILED 的任务实例，**When** 查看批量操作栏，**Then** "Rerun"、"Set Success"、"Kill" 按钮均正常可用。

---

### Edge Cases

- **scheduledFireTime 为 null 的排序行为**: 手动触发和补数据触发的实例 scheduledFireTime 为 null。降序排列时 null 值应排在最末（视为"最小"），升序排列时排在最前。
- **相同时间值的排序**: 当多行 scheduledFireTime 相同时（如同一 cron 批次），按 id（创建顺序）作为次级排序键，保证排序结果稳定不跳跃。
- **并发状态变化**: 实例状态在页面展示期间可能被调度器改变（如 RUNNING→SUCCESS）。按钮状态以页面加载时的数据为准，不实时同步。用户手动刷新后更新。
- **所有选中项均为不可操作状态**: 批量操作按钮全部禁用，显示"所选实例无可用操作"提示。
- **空列表**: 页面正常渲染，排序控件可见但无数据行，不报错。

## Requirements *(mandatory)*

### Functional Requirements

#### 排序

- **FR-001**: 任务流实例列表 MUST 支持按以下列排序：scheduledFireTime、bizDate、startedAt、finishedAt、durationMs
- **FR-002**: 任务实例列表 MUST 支持按以下列排序：scheduledFireTime、bizDate、startedAt、finishedAt、durationMs
- **FR-003**: 默认排序 MUST 为 scheduledFireTime 降序（DESC）
- **FR-004**: 点击可排序列的列头 MUST 切换排序方向：无排序 → 升序（ASC）→ 降序（DESC）→ 无排序
- **FR-005**: 列头 MUST 显示当前排序方向指示器（箭头 icon）
- **FR-006**: 排序状态 MUST 持久化到 URL 参数，自动刷新、切换 Tab、关闭页面后重新打开均保持
- **FR-007**: scheduledFireTime 为 null 的行 MUST 在降序时排最末、升序时排最前
- **FR-008**: 相同排序值下 MUST 以 id 作为次级排序键，保证稳定排序

#### 操作按钮状态化

- **FR-009**: "重跑全部"按钮 MUST 在实例状态为 RUNNING、DISPATCHED、WAITING、PAUSED 时禁用
- **FR-010**: "从失败恢复"按钮 MUST 在实例状态为 FAILED 或 PREEMPTED 时可用，其余状态禁用
- **FR-011**: "停止"按钮 MUST 在实例状态为 SUCCESS、STOPPED 时禁用（允许在 NOT_RUN、WAITING、PAUSED、RUNNING、DISPATCHED、FAILED 时停止）
- **FR-012**: 禁用按钮 MUST 以视觉区分（变灰/降低透明度）并阻止点击事件
- **FR-013**: 批量操作按钮 MUST 在任意选中项处于不可操作状态时禁用，并给出 Tooltip 说明原因
- **FR-014**: 任务实例列表的操作按钮同样 MUST 遵循 FR-009~FR-013 规则

### Key Entities

- **Workflow Instance（任务流实例）**: 已有 state 字段（RUNNING/SUCCESS/FAILED/STOPPED/NOT_RUN/DISPATCHED/WAITING 等），scheduledFireTime/bizDate/startedAt/finishedAt/durationMs 列已存在
- **Task Instance（任务实例）**: 已有 state 字段（同 workflow instance），同样的时间列已存在

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 运维人员打开任务流实例列表时，最新 cron 触发的实例出现在第一行（首屏可见），无需手动翻页查找
- **SC-002**: 运维人员在 3 秒内可通过点击列头完成排序切换（从默认倒序切换到按 bizDate 升序并识别方向）
- **SC-003**: 处于不可操作状态的实例，其对应操作按钮 100% 为禁用态，运维人员无法误触发无效操作
- **SC-004**: 批量操作误点击次数降低至 0（选中混合状态时按钮自动禁用 + Tooltip 说明）

## Assumptions

- 后端 API 已具备 sort 参数扩展能力（`/api/ops/workflow-instances` 和 `/api/ops/instances` 的 query 已接受 page/size/过滤参数，新增 sort 参数遵循相同模式）
- 现有 DataTable 组件具备 client-side 排序列支持或可扩展为 server-side sort
- scheduledFireTime 为 null 的排序约定（降序排末）为合理默认，无需用户配置
- 按钮状态规则适用于当前页面展示的所有操作按钮，不涉及后端校验变更（后端保持不变）
