# Feature Specification: 项目级数据隔离全盘收口 (Project Isolation Sweep)

**Feature Branch**: `036-project-isolation-sweep`

**Created**: 2026-07-01

**Status**: Draft

**Input**: User description: "当前项目隔离的接口，相关的接口是否都已支持，比如指标数据、运维中心调度与运行态总览、日期切换、下面的表格实例联动、日期，以及其他与日期相关的页面、数据隔离等等。全盘扫描要做的数据隔离，还有项目角色隔离、菜单隔离。这是一个大工程，先设计，拆 4 份并发交由外部 AI agent 跑，最终兜底收尾。"

## Clarifications

### Session 2026-07-02

- Q: `cron_fire` / `sla_baseline` 隔离归属：补列还是文档化豁免？ → A: 补列，两张表均加 `project_id` 列 + 索引 + 回填，与告警/质量表一致处理
- Q: 角色/权限矩阵具体内容？ → A: 三级逐级包含（OWNER ⊃ EDITOR ⊃ VIEWER）：VIEWER 只读全部视图+下钻详情，EDITOR 包含 VIEWER + 可编辑任务/工作流/指标定义，OWNER 包含 EDITOR + 管理成员/项目设置/审批
- Q: 存量数据回填的"租户默认项目"如何确定？ → A: 取该租户下创建时间最早的项目作为默认项目
- Q: bizDate 在切换项目时的行为？ → A: 随项目重置，切项目时 bizDate 重置为默认日（T-1），每个项目维护独立的日期状态
- Q: SC-001 全盘扫描清单的格式与存放位置？ → A: `sc-001-isolation-inventory.md`，存放在 spec 目录下，Markdown 表格列举全量接口与隔离状态

## 背景与现状 (Context & Baseline)

平台已在 `032-project-nav` 建立了**项目切换基础设施**：前端有 `ProjectSwitcher` + `useProjectContext`（持久化 `dw.project.current`），后端有 `projects`/`project_member`/`roles`/`user_role`/`role_permission` 表，多数核心业务表（task_def / workflow / instance / metrics / catalog / datasource）已含 `project_id` 列，`ProjectSyncService` 已做项目守卫。

**但隔离并未全盘贯通**，存在系统性缺口（本特性的问题域）：

- **上下文地基缺失**：`TenantContext` 只有 `tenantId/userId`，无 `projectId`；`JwtAuthFilter` 不解析当前项目。→ 请求级无项目身份可用。
- **运维/运行态总览裸奔**：`OpsService.instances()` 用 `findAll()` 全表扫，`OpsController` 端点无 project 参数；前端 ops 三个 panel（周期实例/任务流实例/补数据）不传 `projectId`。
- **指标裸奔**：`MetricsController` 直接 `listLatest()` 全租户返回；前端 `metrics-view` 不传项目、无日期。
- **血缘硬编码**：`LineageService` 写死 `1L,1L`。
- **告警/质量表级缺列**：`alert_*` 仅 `tenant_id`、`quality_*` 无 `project_id`；`cron_fire`/`sla_baseline` 无任何隔离列。
- **角色/菜单零隔离**：`AuthUser` 带 `roles/permissions` 但前端导航与视图**无任何权限过滤**；后端端点无按项目角色的授权校验。
- **日期能力不均**：仅 ops 视图支持 `bizDate` 过滤；metrics / freshness / alerts / reports 无日期切换。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 切项目即换数据，运行态总览与实例表按项目+日期收敛 (Priority: P1)

作为一个隶属多个项目的平台用户，当我在左侧切换"当前项目"时，运维中心的调度与运行态总览、下方的实例表格（周期实例/任务流实例/补数据）、以及它们的下钻联动，**必须只显示当前项目的数据**；切换业务日期时，运行态数据随日期收敛；我**绝不应看到**其他项目的实例、指标或运行记录。

**Why this priority**: 这是"项目隔离"最核心、用户每天都碰、且当前**泄漏最严重**（裸 `findAll()`）的面。单独交付即构成可用 MVP：项目切换从"UI 空壳"变成"真隔离"。

**Independent Test**: 造两个项目 A/B 各含实例数据，登录后切到 A，断言 `/api/ops/instances`、`/api/ops/workflow-instances`、`summary`、`eta-summary` 只返回 A 的行；切 B 只见 B；切日期实例表随之变化；跨项目下钻不串。

**Acceptance Scenarios**:

1. **Given** 项目 A、B 各有周期实例，**When** 用户当前项目为 A 请求实例列表，**Then** 仅返回 A 的实例，B 的一行都不出现。
2. **Given** 用户在项目 A 的运行态总览，**When** 切换 bizDate 到 T-2，**Then** 总览计数与实例表按 T-2 且限定 A 重新收敛。
3. **Given** 实例表某行下钻打开 DAG/日志，**When** 打开详情视图，**Then** 详情携带并沿用同一 projectId，不丢项目上下文。
4. **Given** 用户请求实例但未携带/无权访问的 projectId，**When** 后端校验，**Then** 返回结构化"项目不可访问"错误而非他项目数据。

---

### User Story 2 - 指标与血缘按项目隔离，支持日期观察 (Priority: P1)

作为用户，我查看指标看板与表血缘时，**只能看到当前项目**的指标定义、指标值与血缘图；指标看板支持按业务日期观察当日/历史值；血缘不再对所有人返回同一份硬编码数据。

**Why this priority**: 指标与血缘是"数据资产"面的两大读接口，当前分别是"全租户裸查"与"硬编码 1L/1L"，泄漏与错乱都存在，与 US1 同属 P1 泄漏级。

**Independent Test**: 造两项目指标/血缘，切项目断言接口只返回当前项目；`LineageService` 从上下文取 projectId 而非常量；指标看板加日期后按日期返回对应快照。

**Acceptance Scenarios**:

1. **Given** 项目 A、B 各有原子/衍生指标，**When** 当前项目为 A 请求 `/api/metrics`，**Then** 仅返回 A 的指标。
2. **Given** 血缘图请求，**When** 当前项目为 A，**Then** `LineageService` 以上下文 projectId 查询，返回 A 的表-任务二部图。
3. **Given** 指标看板，**When** 用户选择业务日期，**Then** 指标值按该日期展示，缺数据时给明确空态而非借显他项目/他日期值。

---

### User Story 3 - 告警与质量按项目隔离（含建模补列） (Priority: P2)

作为用户，我在告警中心与数据质量页只看到当前项目的规则、事件、通道与质检结果；平台补齐 `alert_*`/`quality_*` 的项目维度，使这两面从"仅租户隔离"升级到"项目隔离"。

**Why this priority**: 需 schema 变更（加 `project_id` 列 + 索引 + 数据回填 + schema_version 升版），风险与工作量集中且相对独立，适合单独一路推进；泄漏级别低于 US1/US2（已有租户隔离兜底），故 P2。

**Independent Test**: 迁移后造双项目告警/质检数据，断言列表接口按 projectId 过滤；老数据回填到默认项目不丢；两库（PG/H2）DDL 均通过。

**Acceptance Scenarios**:

1. **Given** `alert_rule`/`alert_event`/`alert_channel`/`alert_route` 补 `project_id`，**When** 当前项目为 A 查询告警，**Then** 仅返回 A 的规则与事件。
2. **Given** `quality_rule`/`quality_check_run` 补 `project_id`，**When** 查询质检结果，**Then** 仅返回当前项目的质检。
3. **Given** 迁移前存量数据，**When** 执行 schema 升级，**Then** 存量按其归属租户的默认项目回填，无孤儿行，schema_version 升版且三处（库内/文件头/项目版本）恒等。

---

### User Story 4 - 项目角色隔离与菜单隔离 (Priority: P2)

作为项目成员，我在某项目内的可见菜单/视图与可执行操作由我在该项目的角色决定：VIEWER 只读、无写操作入口；EDITOR 可编辑任务/工作流；OWNER 可管理成员与项目设置。切到我无权限的功能时，菜单不显示、直接访问被后端拒绝。

**Why this priority**: 角色/菜单隔离是"隔离"的权限维度，独立于数据行过滤；当前前端零权限过滤、后端端点无项目角色授权。与 US3 同为 P2（安全增强，但 P1 的数据行隔离是更硬的泄漏底线）。

**Independent Test**: 造 VIEWER/EDITOR/OWNER 三种成员，断言前端导航按角色隐藏对应入口；后端对越权写操作返回结构化拒绝；切项目后角色随项目重算。

**Acceptance Scenarios**:

1. **Given** 用户在项目 A 是 VIEWER，**When** 打开工作台，**Then** 写操作类菜单/按钮不渲染，只读视图可见。
2. **Given** VIEWER 直接构造写请求，**When** 后端校验项目角色，**Then** 返回结构化"权限不足"错误，不执行。
3. **Given** 用户从项目 A（EDITOR）切到项目 B（VIEWER），**When** 切换完成，**Then** 菜单与操作权限按 B 的角色重算。

---

### Edge Cases

- 用户当前项目被删除/被移出成员 → 前端回退到首个可访问项目，后端对失效 projectId 返回可访问项目为空的明确态。
- 请求显式携带的 projectId 与用户 token 身份不匹配（越权探测）→ 一律拒绝，不返回目标项目数据。
- 存量数据无 project_id（迁移前）→ 回填到"该租户最早创建的项目"作为默认项目，不产生跨租户串号。
- 跨项目详情下钻（如从聚合视图跳详情）→ 详情 tab 必须携带 projectId，切项目时关闭失效参数化 tab（复用 032 FR-018 行为）。
- 日期无数据 → 明确空态，禁止借显他项目或他日期数据。
- 切换项目时 bizDate 重置 → 切到新项目 bizDate 回退到 T-1；返回原项目时恢复该项目上次选择的 bizDate（每项目独立记忆）。
- 调度不变量护栏表（`cron_fire`/`sla_baseline` 等）补 `project_id` 列后，现有查询需追加 projectId 过滤条件，确保不破坏调度死锁防御四不变量（只加 WHERE 条件，不改 join/lock 语义）。

## Requirements *(mandatory)*

### Functional Requirements

**地基契约（Foundation — 全局共享，由收尾方先行落地并锁定）**

- **FR-001**: `TenantContext` MUST 携带 `projectId`（当前请求作用的项目）；`JwtAuthFilter`（及 MCP 身份链）MUST 从请求解析并置入 `projectId`，`finally` 清理。
- **FR-002**: 平台 MUST 提供统一的**项目作用域解析与校验**入口（"当前项目 + 该用户在此项目是否为成员/角色"），供所有读写路径复用，越权 projectId 一律拒绝并返回稳定错误码（如 `project.forbidden` / `project.required`）。
- **FR-003**: 前端 MUST 确立统一约定：所有受隔离的数据请求携带当前 `projectId`（读 `useProjectContext`），详情 tab 参数 MUST 透传 projectId。

**数据行隔离（各垂直域，可并行）**

- **FR-010**: 运维中心调度与运行态总览的**全部读接口**（instances / workflow-instances / periodic / backfill / summary / eta-summary 及其 SSE 若涉及）MUST 按当前 projectId 过滤，消除 `findAll()` 裸查。
- **FR-011**: 运行态总览与实例表 MUST 支持业务日期切换，数据按 (projectId, bizDate) 收敛；实例表下钻联动 MUST 保持项目上下文。bizDate 按项目独立维护：切换项目时 bizDate 重置为默认日（T-1），返回原项目时恢复该项目上次选择的日期。
- **FR-012**: 指标读接口（`/api/metrics` 等）MUST 按 projectId 过滤；指标看板 MUST 支持按业务日期观察。
- **FR-013**: 血缘服务 MUST 以上下文 projectId 查询，移除硬编码常量。
- **FR-014**: 告警读写接口 MUST 按 projectId 过滤（依赖 FR-030 的建模补列）。
- **FR-015**: 数据质量读写接口 MUST 按 projectId 过滤（依赖 FR-030）。
- **FR-016**: 其余含隔离列却未收口的读路径（如 freshness/时效、类目运行态）MUST 全部改为按 projectId 过滤——**范围以"全盘扫描清单"为准**（`sc-001-isolation-inventory.md`，与本 spec 同目录，Markdown 表格，逐项标注 已隔离/本次收口/平台级豁免）。

**建模补齐（Schema，独立一路）**

- **FR-030**: `alert_rule`/`alert_event`/`alert_channel`/`alert_route`/`quality_rule`/`quality_check_run`/`cron_fire`/`sla_baseline` MUST 均增加 `project_id` 列（含索引），无豁免，统一按项目隔离。
- **FR-031**: 迁移 MUST 幂等回填存量数据：按租户取最早创建的项目作为默认项目，将 `alert_*`/`quality_*`/`cron_fire`/`sla_baseline` 存量行的 `project_id` 回填为该租户默认项目，无孤儿；PG 与 H2 两方言 DDL 均通过；`schema_version` 升版，库内/文件头/项目版本恒等。

**角色与菜单隔离（独立一路）**

- **FR-040**: 平台 MUST 依据用户在当前项目的角色（默认角色集：VIEWER / EDITOR / OWNER）解析其权限集，采用逐级包含模型：
  - **VIEWER**: 只读全部视图（ops / metrics / lineage / freshness / alerts / quality），可访问下钻详情、日志 SSE；
  - **EDITOR**: VIEWER 全部权限 + 可编辑任务定义、工作流定义、指标定义（含写操作闸门）；
  - **OWNER**: EDITOR 全部权限 + 管理项目成员、项目设置、审批操作。
- **FR-041**: 前端导航与视图 MUST 按当前项目角色过滤：
  - **VIEWER**: 隐藏编辑/创建/发布类入口（任务编辑、工作流编辑、指标编辑、项目设置、成员管理入口），仅展示只读视图导航项；
  - **EDITOR**: 在上述基础上展示编辑类入口；
  - **OWNER**: 展示全部导航项。
  无权限的视图 URL 不可直达（前后端双重守卫）。
- **FR-042**: 后端受保护的写操作端点 MUST 按当前项目角色授权：
  - 任务/工作流/指标定义的创建、更新、删除（EDITOR+）；
  - 项目成员管理、项目设置、审批操作（OWNER only）；
  - 越权返回结构化拒绝（复用现有 `BizException`/闸门语义，不弱化 PolicyEngine）。
- **FR-043**: 切换项目 MUST 触发角色/菜单/权限重算。

**i18n 与一致性**

- **FR-050**: 新增 UI 文案（项目/角色/权限/日期切换/空态/越权提示）MUST 双语（zh-CN/en-US）键集一致；后端新错误码 MUST 稳定不复用并接入 `GlobalExceptionHandler`。

### Key Entities

- **Project（项目）**：隔离的基本作用域单元，隶属 tenant，含 owner；已存在于 `projects`。
- **ProjectMember（项目成员）**：user × project × role 的绑定，决定数据可见与操作权限；已存在于 `project_member`。
- **Role/Permission（角色/权限）**：OWNER/EDITOR/VIEWER 及其权限集；表就位，需接入解析与授权。
- **ProjectScope（项目作用域上下文）**：请求级 (tenantId, userId, projectId) 三元组 + 该用户在此项目的角色，贯穿读写路径（FR-001/FR-002 落地）。
- **业务日期（bizDate）**：运行态/指标的时间维度，与 projectId 联合收敛数据。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001（全盘清单闭环）**: 产出一份"受隔离数据接口全盘清单"（`sc-001-isolation-inventory.md`，Markdown 表格，位于 spec 目录），逐项标注 已隔离/本次收口/平台级豁免；清单中**每个'本次收口'项都有对应测试**证明跨项目不串数据。目标：泄漏级读接口（ops/metrics/lineage/alert/quality/freshness）**100% 收口**。
- **SC-002（零跨项目泄漏）**: 双项目基线数据下，抽测全部受隔离读接口，跨项目串数据条数 = 0。
- **SC-003（日期收敛）**: 运行态总览与指标看板在切换业务日期时，返回数据 100% 落在所选日期与当前项目交集内。
- **SC-004（角色隔离生效）**: VIEWER/EDITOR/OWNER 三种角色下，菜单可见项与可执行写操作与角色矩阵 100% 一致；越权写请求 100% 被后端拒绝。
- **SC-005（迁移无损）**: schema 迁移后存量数据孤儿行 = 0，PG/H2 均通过，schema_version 三处恒等。
- **SC-006（并行可交付）**: 4 路工作流各自可独立编译/测试/演示；地基契约冻结后 4 路无需相互改动即可集成。

## Assumptions

- **隔离层级**：本特性聚焦**租户内的项目级隔离**（tenant 隔离已由现有 TenantContext 承担），不重做跨租户加固。
- **默认角色集**：采用 OWNER/EDITOR/VIEWER 三级；若 `data.sql`/表已有既定角色枚举则以其为准，本特性对齐而非新造。
- **日期语义**：业务日期沿用现有 ops 的 `bizDate` 模型（T-1 兜底、yyyy-MM-dd），对缺日期能力的视图**补齐同一模型**，不引入全新的全局日期选择器架构（除非 US1 集成时证明必要）。
- **统一补列**：`cron_fire`/`sla_baseline` 与告警/质量表统一补 `project_id` 列，不设平台级豁免；迁移需确保不破坏调度死锁防御四不变量（补列不改变现有查询语义，仅加过滤条件）。
- **依赖现有设施**：复用 `useProjectContext`（032）、`ProjectSyncService` 守卫、`GatedActionService`/`PolicyEngine` 闸门、`BizException`/`GlobalExceptionHandler`、`schema.sql` 单一权威 DDL。
- **并行执行前提**：地基（FR-001~003）由收尾方先落地并冻结契约；4 路 agent 各自 git worktree 隔离，仅消费契约、不改地基文件（见 `launch-prompts.md`）。

## 并行工作流拆分 (Parallel Workstream Decomposition)

> 详细的四路启动提示词见 [launch-prompts.md](./launch-prompts.md)。地基由收尾方（本机 Claude）先落地。

| 路 | 名称 | 覆盖 FR | 主要 surface | 冲突面 |
|----|------|---------|--------------|--------|
| **地基（收尾方先行）** | Project scope 契约 | FR-001~003, FR-050(错误码) | `TenantContext`/`JwtAuthFilter`/`McpAuthFilter`/前端 `useProjectContext` 约定 | 全局，先冻结 |
| **A** | 运维/运行态 + 日期 | FR-010/011 | `OpsController`/`OpsService`/ops 三 panel + 日期 | 只碰 ops 域 |
| **B** | 指标 + 血缘 + 时效 + 日期 | FR-012/013/016 | `MetricsController`/`MetricService`/`LineageService`/metrics/freshness view | 只碰 metrics/lineage 域 |
| **C** | 告警 + 质量 + Schema 迁移 | FR-014/015/030/031 | `alert_*`/`quality_*`/`cron_fire`/`sla_baseline` schema+repo+controller+view | 独占 schema.sql 告警/质量/cron/sla 段 |
| **D** | 角色 + 菜单隔离 + i18n | FR-040~043/050 | RBAC 解析、前端导航/视图权限过滤、后端授权 | 只碰权限/导航层 |
