# Feature Specification: 资产目录 + 指标市场 —— 编目、搜索、订阅与复用

**Feature Branch**: `023-asset-catalog-metric-marketplace`

**Created**: 2026-06-30

**Status**: Draft

**Input**: 轨道3「新模块」第 3 份(共 3 份),也是最难一份(面最广、与血缘/目录纠缠最深)。来源:2026-06-30 轨道3 拆解(4 模块→3 份,资产目录与指标市场强内聚,合并为一份)。架构演进路线 [docs/architecture.md](../../docs/architecture.md) §8「资产目录」+「指标市场(搜索/订阅/复用)」。

> **范围边界**:本特性为**元数据发现底座**——把**数据资产(表/数据集)**与**指标**变成可**编目、搜索、订阅、复用**的治理对象,叠加血缘(消费 018-020 neo4j)、质量徽章(消费份2)、owner/分级等元数据。**不**做:血缘图谱本体(018-020 已有,本特性只消费)、质量断言(份2)、通知分发(变更通知复用份1)。**与现有 `CatalogTreeService` 关系**:那是**任务**目录树(文件夹+标签);本特性新建**数据资产**维度,二者是不同对象,仅前端导航整合,不改任务目录树。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 数据资产编目与元数据 (Priority: P1)

数据消费者浏览/检索平台的数据资产(表/数据集),每个资产带 owner/steward、描述、业务术语、标签、敏感度分级、schema 快照,并链接其血缘与质量徽章。

**Why this priority**: 资产目录的根——「知道平台有哪些数据、谁负责、可不可信」是数据治理的第一需求。

**Independent Test**: 对已存在的表,资产条目可被创建/装配并展示 owner/描述/标签/分级/schema/血缘链接/质量徽章;能按名称/标签/owner 检索到。

**Acceptance Scenarios**:

1. **Given** 一张已纳管的表, **When** 查看其资产条目, **Then** 展示 owner/steward、描述、业务术语、标签、敏感度分级、schema 快照。
2. **Given** 该表在 neo4j 有血缘、在份2 有质量分, **When** 查看资产, **Then** 展示血缘入口与质量徽章(消费 018-020 + 份2,不重算)。
3. **Given** 多个资产, **When** 按 owner/标签/敏感度分面检索, **Then** 返回正确过滤结果。

---

### User Story 2 - 分面搜索与发现 (Priority: P1)

用户用关键词 + 分面(类型/owner/标签/敏感度/质量分/新鲜度)搜索资产与指标,快速发现可复用对象。

**Why this priority**: 「市场/目录」的核心动作是发现;无好搜索的目录等于死档案。

**Independent Test**: 关键词命中名称/描述/术语,分面过滤叠加生效,结果按相关度/质量排序,分页有界。

**Acceptance Scenarios**:

1. **Given** 一批资产/指标, **When** 关键词 + 多分面组合搜索, **Then** 返回交集结果,排序与分面计数正确。
2. **Given** 大结果集, **When** 搜索, **Then** 分页有界、不一次性拉爆。

---

### User Story 3 - 指标市场:上架、复用与认证 (Priority: P1)

指标负责人把指标上架到市场(复用现有 metrics 体系),消费者查看指标定义/血缘/owner/新鲜度,复用(引用)而非重复造,认证指标带可信徽章。

**Why this priority**: 指标市场的核心价值——「搜索/订阅/复用」防止指标口径重复与漂移。

**Independent Test**: 指标可上架并被检索;详情展示定义+血缘(消费 018-020 字段级)+owner+认证状态;复用动作产生引用关系而非副本。

**Acceptance Scenarios**:

1. **Given** 现有 metrics 定义, **When** 上架为 `metric_listing`, **Then** 可在市场被搜索,详情展示定义/血缘/owner/新鲜度。
2. **Given** 一个已认证指标, **When** 查看, **Then** 展示认证徽章(可信背书)。
3. **Given** 消费者复用某指标, **When** 复用, **Then** 建立引用关系(可追溯复用方),不产生口径分叉副本。

---

### User Story 4 - 订阅与变更通知 (Priority: P2)

用户订阅资产/指标,当其 schema 变更、质量掉档或新鲜度违约时被通知(复用份1 告警)。

**Why this priority**: 订阅闭合「我依赖的数据变了我要知道」的治理诉求;但目录/搜索/复用即便无订阅也已交付价值,故 P2。

**Independent Test**: 订阅某资产后,模拟其 schema 变更,断言产生 `ASSET_CHANGED` 事件喂份1,按订阅者通道通知。

**Acceptance Scenarios**:

1. **Given** 用户订阅了资产 A, **When** A 的 schema/质量/新鲜度发生约定变更, **Then** 产生 `ASSET_CHANGED` 事件,经份1 通知订阅者。
2. **Given** 退订, **When** 变更发生, **Then** 不再通知该用户。

---

### User Story 5 - 资产/指标市场前端视图 (Priority: P2)

用户在 Workspace 浏览资产目录与指标市场,搜索、查详情、订阅、复用,与任务目录树导航整合。

**Why this priority**: 价值呈现面;核心经 API 可闭环,故 P2。

**Independent Test**: 打开目录/市场视图,能搜索、看详情(元数据+血缘+质量)、订阅、复用;`pnpm typecheck` 零错,双语 key 等集,浏览器验证。

**Acceptance Scenarios**:

1. **Given** 已有资产与指标, **When** 打开视图, **Then** 分面搜索 + 列表 + 详情(元数据/血缘入口/质量徽章)正确呈现。
2. **Given** 任务目录树并存, **When** 导航, **Then** 资产维度与任务目录树整合但不互相破坏。

### Edge Cases

- 资产对应的底层表被删/改名 → 目录 MUST 标「失效/待校验」,不展示陈旧绿灯;依赖血缘/同步态识别。
- 血缘(018-020)或质量(份2)暂不可达 → 资产详情**优雅降级**(隐藏徽章/血缘入口),不阻断目录主功能。
- 搜索超大结果/深分面 → MUST 有分页与上界,不拉爆。
- 指标复用形成环引用(A 复用 B、B 复用 A) → 引用关系 MUST 防环。
- 敏感资产 → 按敏感度分级 + 租户/权限控制可见性,未授权不可见、不可搜出。
- 同一表被多次编目 → 以 `dataset_ref` 唯一约束去重,不产生重复资产条目。
- 多租户:资产/上架/订阅 MUST 按 `tenant_id` 隔离。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供 `data_asset` 编目:绑定 `dataset_ref`(数据源+表,唯一去重)、owner/steward、描述、业务术语、标签、敏感度分级、schema 快照;支持创建/装配/更新。
- **FR-002**: 资产 MUST 链接并**消费** 018-020 neo4j 血缘(展示血缘入口,不重算)与份2 `quality_scorecard`(质量徽章),来源不可达时优雅降级。
- **FR-003**: 系统 MUST 提供分面搜索:关键词命中名称/描述/术语 + 分面(类型/owner/标签/敏感度/质量分/新鲜度)叠加过滤,结果按相关度/质量排序,分页有界。
- **FR-004**: 系统 MUST 提供 `metric_listing` 指标市场:复用现有 metrics 定义上架,详情展示定义 + 血缘(消费 019 字段级)+ owner + 新鲜度 + 认证状态。
- **FR-005**: 系统 MUST 支持指标**复用**:建立引用关系(可追溯复用方),防止口径分叉副本;引用关系 MUST 防环。
- **FR-006**: 系统 MUST 支持指标**认证**(可信徽章),认证为受控写操作,带背书人/时间审计。
- **FR-007**: 系统 MUST 提供 `asset_subscription` 订阅:用户订阅资产/指标;约定变更(schema 变 / 质量掉档 / 新鲜度违约)MUST 产生 `ASSET_CHANGED` 事件喂入份1 告警引擎;退订后不再通知。
- **FR-008**: 资产/上架/订阅 MUST 按 `tenant_id` 隔离(沿用 `TenantContext`),并按敏感度分级 + 权限控制可见性;未授权不可见、不可搜出。
- **FR-009**: 资产/上架/认证/订阅的写(agent 发起)MUST 经 `ActionRequest → GatedActionService.submit → PolicyEngine` + `agent_action` 审计,无旁路;UI admin CRUD 走普通鉴权 API + 审计。
- **FR-010**: `data_asset` 以 `dataset_ref` 唯一约束去重;底层表失效(删/改名)时 MUST 标失效态而非展示陈旧可信信息(依赖血缘/同步态识别)。
- **FR-011**: 系统 MUST 暴露 `CatalogMetrics`(Micrometer):资产/指标数、搜索 QPS/延迟、订阅数;经 `/actuator/prometheus` + `/api/ops/metrics`。
- **FR-012**: 错误 MUST 走 `BizException(code, args)` + `GlobalExceptionHandler`,错误码 `catalog.<semantic>`(如 `catalog.asset_not_found`/`catalog.duplicate_asset`/`catalog.tenant_required`),稳定不复用;数据术语(schema/lineage/metric)保留英文。
- **FR-013**: 前端 MUST 提供资产目录 + 指标市场视图(分面搜索/列表/详情/订阅/复用),与现有任务 `CatalogTree` 导航整合但不改其对象模型;注册进 Workspace view registry,遵循 DESIGN.md 与前端栈约定;静态文案走 next-intl(zh-CN/en-US key 等集)。
- **FR-014**: 新增 `data_asset`/`metric_listing`/`asset_subscription` 等表 MUST 写入权威 `schema.sql` 并**升 `schema_version`**(MINOR;三处恒等,SemVer),H2/PG 双方言兼容。

### Key Entities *(include if feature involves data)*

- **data_asset**: 数据资产条目。`id, tenant_id, dataset_ref(唯一), name, description, owner, steward, glossary_terms, tags, sensitivity, schema_snapshot_json, lineage_node_ref, status(ACTIVE|STALE|RETIRED), created/updated`。
- **metric_listing**: 指标市场上架。`id, tenant_id, metric_ref(复用现有 metrics), owner, certification(NONE|CERTIFIED), certified_by/at, freshness, description`。
- **metric_reuse_ref**: 指标复用引用(防环)。`id, tenant_id, listing_id, consumer_ref, created_at`。
- **asset_subscription**: 订阅。`id, tenant_id, subscriber, target_type(ASSET|METRIC), target_id, change_filter(schema|quality|freshness), created_at`。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 数据资产可编目并展示完整元数据(owner/描述/术语/标签/分级/schema),按 `dataset_ref` 去重无重复条目。
- **SC-002**: 资产/指标详情正确消费 018-020 血缘与份2 质量徽章;来源不可达时优雅降级,目录主功能 100% 不受影响。
- **SC-003**: 分面搜索:关键词 + 多分面叠加返回正确交集,排序与分面计数正确,分页有界(可量化断言)。
- **SC-004**: 指标可上架/检索/复用;复用产生可追溯引用关系且防环(反证测试);认证徽章正确呈现。
- **SC-005**: 订阅资产后约定变更 100% 产生 `ASSET_CHANGED` 事件,接缝可被份1 消费通知;退订后不再通知。
- **SC-006**: 跨租户 + 敏感度可见性隔离 100%——未授权资产不可见、不可搜出。
- **SC-007**: 资产/上架/认证/订阅的 agent 写全部经 PolicyEngine 闸门 + `agent_action` 审计,零旁路。
- **SC-008**: 前端目录/市场视图 `pnpm typecheck` 零错、双语 key 等集(CI 校验)、浏览器验证搜索/详情/订阅/复用;与任务目录树整合不互坏。
- **SC-009**: `schema_version` 三处恒等且升版;H2 与 PG 双库 DDL 均通过。

## Assumptions

- 血缘消费 018-020 neo4j 只读会话(表/列/指标节点已在图中),本特性不写血缘、不重算。
- 质量徽章消费份2 `quality_scorecard`,本特性不算质量。
- 指标复用现有 metrics 体系(immutable + version 范式),`metric_listing` 是其市场化包装层,不改 metrics 写入语义。
- 变更通知复用份1 告警(`ASSET_CHANGED` 为份1 可消费的 signal_source),不另造通知栈。
- 现有 `CatalogTreeService` 是任务目录,本特性新建数据资产维度独立对象,仅前端导航整合;不统一成单一树(对象语义不同)。
- 搜索实现选型(PG 全文检索 / 既有)留 plan;spec 层只约束「分面 + 关键词 + 有界分页 + 相关度排序」能力。
- 敏感度分级以基线枚举(PUBLIC/INTERNAL/CONFIDENTIAL/PII)起步,具体策略留 plan。
