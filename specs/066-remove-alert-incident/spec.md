# Feature Specification: 移除人工告警/事件/质量/工单体系（为 Agent 智能运维清场）

**Feature Branch**: `066-remove-alert-incident`

**Created**: 2026-07-12

**Status**: Draft

**Input**: User description: "移除告警中心、事件中心、故障工单、数据质量模块及 AlertSignal 信号桥体系，为 Agent 智能运维清场。Agent 后续有自己的巡检日志，全流程 AI 处理无需人工介入。"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 移除告警中心（规则/渠道/通知）(Priority: P1)

平台不再提供人工配置告警规则、通知渠道（邮件/Webhook）、告警事件查看、告警静默这套能力。故障检测与处置改由后续 Agent 智能运维的自动巡检承担，告警中心作为"人工配置规则→通知人工介入"的载体彻底下线。

**Why this priority**: 告警中心是人工运维介入的核心入口，与"全流程 AI 处理无需人工介入"直接冲突，是最优先要清空的人工运维载体。

**Independent Test**: 告警相关页面、API 路由、数据表、策略种子全部消失后，前端无告警入口、后端编译零错、应用可正常启动，即可独立验证。

**Acceptance Scenarios**:

1. **Given** 告警中心页面与 `/api/alert/*` 路由存在, **When** 完成移除, **Then** 前端导航无"告警中心"入口，访问告警 API 返回 404，前端无告警相关组件残留
2. **Given** `alert_*` 数据表存在, **When** 移除, **Then** schema 不再含这些表定义，应用在 H2 与 PostgreSQL 下均能正常启动
3. **Given** 闸门策略含告警相关权限规则, **When** 移除, **Then** 这些策略种子从种子数据中删除，闸门不再识别告警相关动作

---

### User Story 2 - 移除 AlertSignal 故障信号桥 (Priority: P1)

master 不再向告警消费侧发布故障信号（任务失败/超时、SLA 风险、节点离线、节点饥饿、任务 SUSPENDED、工作流状态等）。Agent 智能运维将自建巡检日志机制发现故障，不复用现有信号桥。

**Why this priority**: 信号桥是现有故障可见性的骨架；保留即留下"半套人工告警"，与彻底清场矛盾。信号桥唯一现存消费者就是告警中心，告警中心删后它成为死发布。

**Independent Test**: master 各发布点不再发布故障信号，信号类与监听消费者消失，而调度核心逻辑（状态机的状态推进、CAS、锁序）不变，即可独立验证。

**Acceptance Scenarios**:

1. **Given** 任务/工作流终态与异常分支发布故障信号, **When** 移除, **Then** 所有发布调用与私有 helper 方法消失，但状态机的状态推进、CAS、锁序完全不变
2. **Given** 5 个发布类各自持有事件发布器, **When** 移除, **Then** 仅用于故障信号的发布器字段一并删除；若发布器还服务于其他事件则保留字段、只删故障信号调用
3. **Given** 调度死锁防御不变量（SKIP LOCKED 认领 / 乐观 CAS / 固定锁序 / 事务内持久）, **When** 移除信号桥, **Then** 真实并发 dispatch 仍满足 `started_at − created_at ≈ 0`、根节点 `attempt=1`、零"跳过下发/中止执行"stragglers

---

### User Story 3 - 移除数据质量模块 (Priority: P1)

平台不再提供人工配置数据质量断言、质量门禁、质量记分卡、质量检查执行、质量探针这套能力。质量模块作为"人工配置断言+门禁拦截"的人工运维介入载体一并下线。

**Why this priority**: 与告警/工单同属人工运维介入体系，同为 Agent 清场让路。主体已由并行工作删除，本特性负责收尾与验证。

**Independent Test**: quality 包全部消失后，现存代码对 quality 包的引用为零、种子数据无 quality 策略、应用正常编译启动，即可独立验证。

**Acceptance Scenarios**:

1. **Given** quality 模块（断言/门禁/记分卡/检查/探针）存在, **When** 移除, **Then** 后端 quality 包、前端质量视图、worker 质量探针执行器全部删除
2. **Given** 闸门策略含质量相关权限规则, **When** 移除, **Then** 种子数据中的质量策略删除
3. **Given** 任务成功后触发质量门禁钩子, **When** 移除, **Then** 该钩子删除，任务成功的状态推进主逻辑不变

---

### User Story 4 - 清理 incident/event/health 残留 (Priority: P2)

前置特性（065 移除监督席）已移除故障工单、事件中心、健康事件的主体代码与数据表。本特性收尾其残留：孤儿国际化 key、规格目录处置。

**Why this priority**: 主体已删，残留无功能影响，仅整洁性与一致性。

**Independent Test**: 国际化资源无 incident 孤儿 key、规格目录处置明确，即可独立验证。

**Acceptance Scenarios**:

1. **Given** 后端国际化资源含已删工单的孤儿 key, **When** 清理, **Then** 这些 key 删除
2. **Given** 已下线特性的规格目录存在, **When** 处置, **Then** 保留作历史决策记录（不删规格目录）

---

### User Story 5 - 调度核心与闸门不退化 (Priority: P2)

删除只动旁路信号/告警/质量钩子，不碰调度核心（状态机的 CAS/锁/状态转移）与 PolicyEngine 闸门（L0-L4 分级 + agent_action 审计）的不变量。

**Why this priority**: 删除不能伤承重墙。调度死锁防御与闸门是平台基线，任何删除波及这些核心都必须证明不退化。

**Independent Test**: 全量测试套件全绿 + 真实并发 dispatch 端到端核验通过，即可独立验证。

**Acceptance Scenarios**:

1. **Given** 删除波及任务实例状态机, **When** 验证, **Then** master 调度测试套件全绿，真实并发 dispatch 零 stragglers
2. **Given** 删除波及平台动作执行器, **When** 验证, **Then** 闸门 L0-L4 分级与 agent_action 审计行为不变
3. **Given** 删除波及前端, **When** 验证, **Then** 前端类型检查与组件测试零错

---

### Edge Cases

- 某发布点的事件发布器若还服务于其他事件（如就绪态 outbox），只删故障信号相关调用，不删发布器字段
- 种子数据中引用外部业务库 `data_quality.alerts` 表的示例 SQL 任务——这是任务 SQL 内容、非平台模块，保留
- 故障信号若存在未识别的消费者——删除前全仓 grep 确认零消费者
- schema 版本已在前置特性升至 0.17.0；本特性再删告警/质量表，需再次升版本并保持 DB 行/文件头/项目版本一致

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 移除告警中心全部能力（规则/通道/路由/事件/通知/静默/轮询触发）及其前端入口与 API 路由
- **FR-002**: 系统 MUST 移除 AlertSignal 故障信号桥——信号类、所有发布点、以及告警侧的监听消费者全部删除
- **FR-003**: 系统 MUST 移除数据质量模块全部能力（断言/门禁/记分卡/检查执行/探针）及其前端入口与 API
- **FR-004**: 系统 MUST 清理种子数据中所有告警与质量相关的闸门策略规则
- **FR-005**: 系统 MUST 清理 schema 中告警与质量相关表定义，并升级 schema 版本（DB 行/文件头/项目版本一致）
- **FR-006**: 系统 MUST 清理 incident/event/health 的国际化孤儿 key 残留
- **FR-007**: 删除 MUST NOT 改变调度核心逻辑（任务实例状态机的 CAS/锁/状态转移）与 PolicyEngine 闸门（L0-L4 分级 + agent_action 审计）的不变量
- **FR-008**: 系统 MUST 在 H2（内存）与 PostgreSQL（DROP+CREATE）两种存储下正常启动
- **FR-009**: 系统 MUST 保持前端国际化两语言包的 key 集合一致（parity）
- **FR-010**: 已下线特性的规格目录（027/043/064）MUST 保留作历史决策记录

### Key Entities *(include if feature involves data)*

- **告警中心**（待删）：告警规则、通知通道、路由、告警事件、通知、静默、轮询触发
- **AlertSignal 信号桥**（待删）：故障信号类型枚举 + 5 个发布点（任务状态机、SLA 服务、租约回收器、卡住扫描器、超时扫描器）
- **数据质量模块**（待删）：质量规则、检查运行、断言、门禁、记分卡、探针
- **调度核心**（保留）：任务实例状态机、CAS 调度、死锁防御四不变量
- **PolicyEngine 闸门**（保留）：L0-L4 风险分级、agent_action 审计

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 平台不再含任何告警/事件/质量/工单相关代码、数据表、API 路由、前端入口——全仓 grep 零命中
- **SC-002**: 全后端编译零错，全量测试套件（master/api/worker/alert 各模块）全绿，无新增失败测试
- **SC-003**: 真实并发调度端到端核验通过：`started_at − created_at ≈ 0`、根节点 `attempt=1`、零"跳过下发/中止执行"stragglers
- **SC-004**: 前端类型检查与组件测试零错，国际化两语言包 key 集合完全一致
- **SC-005**: H2（内存）与 PostgreSQL（DROP+CREATE）两种存储下应用均能正常启动
- **SC-006**: schema 版本正确升级，且 DB 行 / 文件头 / 项目版本三者一致

## Assumptions

- Agent 智能运维是后续独立特性，本特性只做清场，不设计 Agent 巡检机制
- 前置特性 065（移除监督席）已提交落袋，本特性不回滚它，只收尾其残留
- quality 模块删除的并行工作并入本特性，由本特性接手完成与验证
- 工作区现有的 quality 未提交删除是有效的、符合用户意图，在此基础上继续
- worker 容量检测改动已由前置提交单独落袋，本特性不涉及
- 种子数据中引用外部业务库 `data_quality.alerts` 的示例 SQL 任务保留（非平台模块）
- 无生产遗留告警/质量数据需迁移（开发项目，PostgreSQL 用 DROP+CREATE 重建）
