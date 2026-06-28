# Feature Specification: 工作流实例运维操作

**Feature Branch**: `006-workflow-instance-ops`

**Created**: 2026-06-26

**Status**: Draft

**Input**: User description: "数据库中任务流实例有哪些字段，可操作的点，重跑，置成功，停止，等操作"

## Clarifications

### Session 2026-06-26

- Q: 运维操作的权限模型是什么？是否需要审批流程？ → A: 所有操作（停止/重跑/置成功/暂停/恢复/批量操作）由运维人员直接执行，无需审批确认
- Q: DEV 环境（画布试运行）实例的运维操作与 PROD 有何不同？ → A: DEV 实例仅支持停止，不支持重跑/置成功/暂停/恢复（DEV 是调试场景，试运行失败后用户直接在画布重新触发）
- Q: 批量操作单次可选中多少个实例？ → A: 单次最多 100 个
- Q: 本 feature 的实现范围是前端为主还是前后端并重？ → A: 前后端并重 —— 前端构建运维操作交互界面，后端可能需要补充或调整部分操作逻辑以适配前端交互需求

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 查看实例详情与状态 (Priority: P1)

运维人员需要查看某个工作流实例的完整信息 —— 包括基础字段（触发类型、业务日期、环境、优先级）、运行状态（NOT_RUN/WAITING/RUNNING/SUCCESS/FAILED/STOPPED/PAUSED）、以及进度指标（总任务数、已完成数、失败数），以便快速判断当前健康状况并决定后续操作。

**Why this priority**: 所有操作的前提是用户能看到实例当前状态；没有可见性就无法决策。

**Independent Test**: 打开任意工作流实例详情页，能看到上述全部字段且值与数据库一致。

**Acceptance Scenarios**:

1. **Given** 系统中存在一个状态为 RUNNING 的工作流实例, **When** 用户打开该实例详情, **Then** 展示实例 ID、工作流名称/版本、触发类型、业务日期(biz_date)、环境(env)、优先级(priority)、当前状态(state)、开始时间(started_at)、总/已完成/失败任务计数(total_tasks/completed_tasks/failed_tasks)
2. **Given** 系统中存在一个状态为 FAILED 的工作流实例, **When** 用户打开该实例详情, **Then** 额外展示失败任务列表及各自的失败原因(failure_reason)，以及 finished_at 结束时间
3. **Given** 系统中存在一个状态为 SUCCESS 的工作流实例, **When** 用户打开该实例详情, **Then** 展示 started_at 和 finished_at，以及总耗时

---

### User Story 2 - 停止运行中的实例 (Priority: P1)

当工作流实例运行异常（死循环、资源耗尽、数据错误）或需要紧急让路给更高优先级的任务时，运维人员需要能立即停止该实例，将所有未完成的任务节点标记为 STOPPED 状态。

**Why this priority**: 停止是应急响应的核心操作，直接关系到系统稳定性和资源保护。

**Independent Test**: 对一个 RUNNING 状态的实例执行停止操作，验证实例和所有子任务变为 STOPPED。

**Acceptance Scenarios**:

1. **Given** 一个状态为 RUNNING 的工作流实例，其中包含多个 WAITING/DISPATCHED/RUNNING 的任务节点, **When** 用户对该实例执行"停止"操作, **Then** 工作流实例状态变为 STOPPED，所有非终态任务节点变为 STOPPED，已 SUCCESS/FAILED/SKIPPED 的节点不受影响
2. **Given** 一个状态为 FAILED 的工作流实例, **When** 用户尝试对该实例执行"停止"操作, **Then** 操作被拒绝，提示"实例已处于终态，无法停止"
3. **Given** 一个状态为 SUCCESS 的工作流实例, **When** 用户尝试对该实例执行"停止"操作, **Then** 操作被拒绝，提示"实例已成功完成，无需停止"

---

### User Story 3 - 重跑失败/已停止的实例 (Priority: P1)

当工作流实例因数据延迟、资源不足或代码缺陷等可恢复原因失败或被停止后，运维人员需要能"重跑"该实例 —— 将所有节点重置为 WAITING 状态，让调度器重新调度执行。

**Why this priority**: 重跑是最高频的恢复操作，直接影响数据产出的及时性。

**Independent Test**: 对一个 FAILED 实例执行重跑，验证所有节点重置为 WAITING 且调度器重新调度。

**Acceptance Scenarios**:

1. **Given** 一个状态为 FAILED 的工作流实例，其中部分节点为 SUCCESS、部分为 FAILED, **When** 用户对该实例执行"重跑全部"操作, **Then** 所有节点（包括原本 SUCCESS 的）重置为 WAITING，工作流实例状态变为 RUNNING，调度器开始重新调度
2. **Given** 一个状态为 FAILED 的工作流实例，其中部分节点为 SUCCESS、部分为 FAILED, **When** 用户对该实例执行"从失败点恢复"操作, **Then** 仅 FAILED 和下游未执行节点重置为 WAITING，已 SUCCESS 的节点保持不变，工作流实例状态变为 RUNNING
3. **Given** 一个状态为 STOPPED 的工作流实例, **When** 用户对该实例执行"重跑"操作, **Then** 所有节点重置为 WAITING，工作流实例状态变为 RUNNING
4. **Given** 一个状态为 RUNNING 的工作流实例, **When** 用户尝试对该实例执行"重跑"操作, **Then** 操作被拒绝，提示"实例正在运行中，无法重跑"

---

### User Story 4 - 强制置成功单个任务 (Priority: P2)

当单个任务节点因已知的非关键原因失败（如上游数据延迟已恢复、已知的数据质量告警无需处理），而其他节点运行正常时，运维人员需要能将该失败节点"强制置为成功"，让下游依赖节点继续执行，无需重跑整个工作流。

**Why this priority**: 精细化的单节点操作可以减少不必要的全量重跑，节省计算资源，但需谨慎使用故优先级次于全量重跑。

**Independent Test**: 对一个 FAILED 状态的任务节点执行"置成功"，验证该节点变为 SUCCESS 且下游节点被唤醒。

**Acceptance Scenarios**:

1. **Given** 一个工作流实例中某个任务节点状态为 FAILED（其下游节点处于 WAITING 等待上游完成）, **When** 用户对该任务节点执行"置成功"操作, **Then** 该节点状态变为 SUCCESS，下游 WAITING 节点被唤醒进入调度队列
2. **Given** 一个任务节点状态为 NOT_RUN, **When** 用户尝试对该节点执行"置成功"操作, **Then** 操作被拒绝，提示"仅允许对 FAILED/STOPPED/RUNNING/PREEMPTED 状态节点执行置成功"
3. **Given** 一个任务节点状态为 SUCCESS, **When** 用户尝试对该节点执行"置成功"操作, **Then** 操作被拒绝，提示"该节点已为成功状态"

---

### User Story 5 - 暂停/恢复工作流实例 (Priority: P2)

运维人员需要能"暂停"正在运行的工作流实例（例如在数据源维护窗口期间暂停消费），待条件恢复后再"继续"执行。

**Why this priority**: 暂停/恢复是运维窗口期的标准操作，使用频率低于停止和重跑。

**Independent Test**: 对一个 RUNNING 实例执行暂停，验证未开始的任务变为 PAUSED；再执行恢复，验证 PAUSED 任务变为 NOT_RUN 并继续调度。

**Acceptance Scenarios**:

1. **Given** 一个状态为 RUNNING 的工作流实例，其中部分节点为 NOT_RUN, **When** 用户对该实例执行"暂停"操作, **Then** 工作流实例状态变为 PAUSED，所有 NOT_RUN 状态的任务节点变为 PAUSED，正在 RUNNING 的节点继续运行至完成
2. **Given** 一个状态为 PAUSED 的工作流实例, **When** 用户对该实例执行"恢复"操作, **Then** 工作流实例状态变为 RUNNING，所有 PAUSED 状态的任务节点变为 NOT_RUN，调度器恢复调度
3. **Given** 一个状态为 FAILED 的工作流实例, **When** 用户对该实例执行"暂停"操作, **Then** 操作被拒绝，提示"无法暂停已终态的实例"

---

### User Story 6 - 单任务节点操作 (Priority: P3)

运维人员需要对工作流实例中的单个任务节点执行独立操作：停止单个卡死的任务、重跑单个失败的任务、暂停/恢复单个节点。

**Why this priority**: 单节点操作提供最精细的控制粒度，是对实例级操作的补充，使用频率较低但不可或缺。

**Independent Test**: 对一个 RUNNING 工作流实例中的单个 DISPATCHED 任务执行停止，验证仅该任务变为 STOPPED，其他节点不受影响。

**Acceptance Scenarios**:

1. **Given** 一个工作流实例中某个任务节点状态为 DISPATCHED（可能卡在 Worker 上）, **When** 用户对该节点执行"停止"操作, **Then** 该节点状态变为 STOPPED，工作流实例状态按聚合规则重新计算
2. **Given** 一个工作流实例中某个任务节点状态为 FAILED, **When** 用户对该节点执行"重跑"操作, **Then** 该节点重置为 WAITING（清除 worker/lease/attempt/failure_reason），被调度器重新调度
3. **Given** 一个工作流实例中某个任务节点状态为 NOT_RUN, **When** 用户对该节点执行"暂停"操作, **Then** 该节点变为 PAUSED，不会被调度器调度

---

### User Story 7 - 批量操作 (Priority: P3)

当多个工作流/任务实例需要同时处理时（如上游数据修复后需批量重跑），运维人员需要能一次性选择多个目标执行批量停止、批量重跑或批量置成功。

**Why this priority**: 批量操作提升大规模运维效率，但使用场景集中在数据平台级故障恢复，日常频率较低。

**Independent Test**: 选择 3 个不同工作流的 FAILED 实例执行批量重跑，验证全部 3 个实例进入 RUNNING 状态。

**Acceptance Scenarios**:

1. **Given** 用户筛选出 5 个状态为 FAILED 的任务实例, **When** 用户选中它们并执行"批量重跑", **Then** 所有 5 个实例被重置为 WAITING，每个操作结果独立返回（含成功/失败详情）
2. **Given** 用户选中 3 个运行中的任务实例, **When** 用户执行"批量停止", **Then** 所有 3 个实例被停止，操作结果独立返回

---

### Edge Cases

- 当工作流实例处于终态（SUCCESS/FAILED/STOPPED/SKIPPED）时，停止操作应被拒绝并给出明确原因
- 当工作流实例处于非终态（RUNNING/WAITING/NOT_RUN）时，重跑操作应被拒绝
- 置成功操作仅对 FAILED/STOPPED/RUNNING/PREEMPTED 状态有效，NOT_RUN/WAITING/PAUSED 应被拒绝
- 并发操作冲突：当两个用户同时对同一实例执行操作时，系统保证只有一个成功，另一个应收到明确错误提示
- DAG 中存在冻结节点时，冻结节点在重跑后应保持 SKIPPED 状态（不被重置为 WAITING）
- 对 DEV 环境（画布试运行）的实例，仅提供"停止"操作，不提供重跑/置成功/暂停/恢复
- 批量操作选中超过 100 个实例时，系统应提示用户减少选择或分批执行

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 展示工作流实例的完整字段信息：ID、所属工作流/版本、触发类型、环境(env)、业务日期(biz_date)、优先级(priority)、状态(state)、总/已完成/失败任务计数(total_tasks/completed_tasks/failed_tasks)、开始/结束时间(started_at/finished_at)
- **FR-002**: 系统 MUST 允许用户对 RUNNING 状态的工作流实例执行"停止"操作，将所有非终态任务节点转换为 STOPPED，工作流实例状态随之聚合为 STOPPED
- **FR-003**: 系统 MUST 允许用户对终态（FAILED/STOPPED/SUCCESS）工作流实例执行"重跑全部"操作，将所有任务节点重置为 WAITING
- **FR-004**: 系统 MUST 允许用户对 FAILED 状态工作流实例执行"从失败点恢复"操作，保留 SUCCESS 节点，仅重置 FAILED 及其下游节点
- **FR-005**: 系统 MUST 允许用户对 FAILED/STOPPED/RUNNING/PREEMPTED 状态的任务节点执行"置成功"操作，将其强制变为 SUCCESS 并唤醒下游依赖
- **FR-006**: 系统 MUST 允许用户对 RUNNING 状态的工作流实例执行"暂停"操作，将 NOT_RUN 节点变为 PAUSED；对 PAUSED 状态执行"恢复"操作，将 PAUSED 节点变为 NOT_RUN
- **FR-007**: 系统 MUST 支持对单个任务节点执行停止、重跑、暂停、恢复操作
- **FR-008**: 系统 MUST 支持批量操作（批量停止、批量重跑、批量置成功），单次最多 100 个实例，每个操作结果独立返回
- **FR-009**: 系统 MUST 对不允许的操作组合返回明确的拒绝原因（如对已终态实例停止、对运行中实例重跑等）
- **FR-010**: 所有写操作 MUST 通过状态前置条件校验执行，确保并发操作安全；并发冲突时一方成功、另一方收到明确错误提示
- **FR-011**: 所有写操作 MUST 由运维人员直接执行（无需审批确认），操作结果即时生效
- **FR-012**: 系统 MUST 在操作执行后记录审计日志，包含操作人、操作类型、目标实例、执行结果，支持事后追溯
- **FR-013**: DEV 环境（env=DEV）的实例 MUST 仅展示"停止"操作入口，不展示重跑/置成功/暂停/恢复/批量操作

### Key Entities

- **工作流实例 (WorkflowInstance)**: 一次工作流执行的完整记录。关键属性：id（UUID）、workflow_id（所属工作流定义）、state（聚合状态：NOT_RUN/WAITING/RUNNING/PAUSED/SUCCESS/FAILED/STOPPED）、trigger_type（CRON/MANUAL/BACKFILL）、env（PROD/DEV）、biz_date（业务日期）、priority（0-9）、total_tasks/completed_tasks/failed_tasks（进度计数）、started_at/finished_at（时间戳）、version（乐观锁版本号）
- **任务实例 (TaskInstance)**: 工作流实例中单个任务节点的执行记录。关键属性：id（UUID）、workflow_instance_id（所属工作流实例）、task_id（所属任务定义）、state（节点状态，比工作流多 DISPATCHED/PREEMPTED/SKIPPED）、worker_node_code（执行 Worker）、attempt（重试次数）、failure_reason（失败原因）、lease_expire_at（租约过期时间）、exit_code（退出码）
- **操作审计记录 (AgentAction)**: 每次运维操作的审计轨迹。关键属性：session_id、run_id、step_id、action_type（操作类型如 OPS_RERUN_INSTANCE/KILL_INSTANCE/OPS_SET_SUCCESS）、target_id、outcome（SUCCESS/REJECTED/PENDING_APPROVAL）、created_at

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 用户可在 10 秒内定位目标工作流实例并完成一次停止或重跑操作
- **SC-002**: 所有操作的状态转换在 2 秒内完成（不包括审批等待时间）
- **SC-003**: 并发操作冲突时，100% 的情况一方成功、另一方收到明确错误提示，不存在"静默双写"导致数据不一致
- **SC-004**: 100% 的写操作产生审计记录，可追溯操作人、时间、类型和结果
- **SC-005**: 操作拒绝（如非法状态转换）时，100% 返回包含拒绝原因的提示信息，用户无需猜测失败原因
- **SC-006**: 批量操作中单个子项失败不影响其他子项，每项独立返回结果

## Assumptions

- 工作流实例的状态由所有子任务节点状态按聚合规则自动计算，用户不直接修改工作流实例状态
- DEV 环境（画布试运行）实例仅保留停止操作；PROD 环境实例支持全部运维操作（停止/重跑/置成功/暂停/恢复/批量）
- 操作权限依赖现有的认证机制，运维人员登录后即可执行所有运维操作，无需额外审批
- 冻结节点的冻结状态在重跑时保持（不会被重置为待执行）
- 前端运维操作界面的交互细节（按钮位置、确认对话框样式等）由后续设计阶段确定
- 后端已有全部操作 API 基础实现，但可能需要根据前端交互需求进行补充或调整（如批量操作的事务边界、操作前置校验的错误码细化等）
