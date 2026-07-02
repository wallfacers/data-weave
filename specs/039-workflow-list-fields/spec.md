# Feature Specification: 周期/手动任务流列表字段增强

**Feature Branch**: `039-workflow-list-fields`

**Created**: 2026-07-02

**Status**: Draft

**Input**: User description: "运维中心「周期任务流」「手动任务流」两个卡片当前列偏少（周期 7 列 / 手动 6 列），需调研 `workflow_def` 数据库字段，挑选运维必须展示的字段补充进列表。"

## 字段调研结论（背景依据）

> 本节回应「数据库有哪些字段、哪些必须展示」——是后续需求的依据。

**`workflow_def` 表字段全集**（24 个，权威 DDL 见 `schema.sql`）：
`name` · `description` · `schedule_type`(MANUAL/CRON/DEPENDENCY) · `cron` · `schedule_start` · `schedule_end` · `status` · `current_version_no` · `has_draft_change` · `last_fire_time` · **`next_trigger_time`** · `schedule_interval_ms` · **`priority`(0–9)** · **`preemptible`** · **`timeout_sec`** · `created_by` · `updated_by` · `created_at` · `updated_at` · `catalog_node_id` · …（+ tenant/project/id/deleted/version）

**后端 `WorkflowListRow` DTO 已投影**（12 个，已下发前端）：
name · description · cron · status · currentVersionNo · hasDraftChange · lastFireTime · **priority** · **timeoutSec** · **updatedAt** · **updatedBy(仅id)** · catalogNodeId · recentTriggerResult

**前端两卡片当前已展示**：
- 周期：名称 / Cron / 最近触发 / 状态 / 上次运行 / 版本 / 操作（7 列）
- 手动：名称 / 最近触发 / 状态 / 上次运行 / 版本 / 操作（6 列）

**三层差集**：

| 类别 | 字段 | 成本 | 说明 |
|---|---|---|---|
| A·DTO 已返回但前端未展示 | `priority` / `timeoutSec` / `description` / `updatedAt` | **零后端成本** | 直接补列即可 |
| B·表里有但 DTO 未投影 | **`next_trigger_time`** / `preemptible` / `schedule_start`/`end` / `created_by`/`at` | 中（后端 SQL 补投影） | 需改 DTO + 列表 SQL |
| C·需 join 其它表 | 负责人名字(join `sys_user`) / 类目路径(递归 join `catalog_node`) / 节点任务数(聚合 `workflow_node`) | 高 | 本 spec 不做，拆后续（与 038 哲学一致） |

**结论**：高性价比补充集中在 A + B 两类。最具运维价值的是 **`next_trigger_time`（下次触发时间）**——周期调度"下次何时跑"的核心心智，当前列只有"上次运行"(向后看)却缺"下次触发"(向前看)；其次是 **`priority`**（抢占排障定位，DTO 现成、零成本）。

### 已确认补充字段（C 方案）

| 卡片 | 补充列 | 来源 | 成本 | 运维理由 |
|---|---|---|---|---|
| 周期任务流 | **下次触发时间** | B（后端补投影） | 中 | 周期调度核心信息，"下次什么时候自动跑" |
| 周期任务流 | **优先级** | A（DTO 现成） | 零 | 抢占/优先级定位（0–9） |
| 周期任务流 | **描述**（名称副标题） | A（DTO 现成） | 零 | 信息密度增强，不占独立列 |
| 手动任务流 | **优先级** | A（DTO 现成） | 零 | 同上 |
| 手动任务流 | **描述**（名称副标题） | A（DTO 现成） | 零 | 同上 |

> 字段选型已确认为 **C 方案**（B + A 类增强）；详见 `## Clarifications`。`timeout_sec` 暂不单列（避免周期流列数过多），如需可后续追加。

## Clarifications

### Session 2026-07-02

- Q: 补充字段集选型（specify 阶段方案表 A/B/C）→ A: **C** —— 在 B（周期=下次触发时间+优先级；手动=优先级）基础上，再增强 A 类现成字段「描述」作名称下方副标题。落地：周期=下次触发时间+优先级+描述副标题；手动=优先级+描述副标题。`timeout_sec` 暂不单列。
- Q:「下次触发时间」列展示形态 → A: **B** —— 相对时间（如"3 小时后"/"已过期 2m"），需新增前端相对时间 util + i18n 文案（覆盖未来/已过期/临近三态，见 FR-008）。
- Q:「优先级」列展示形态 → A: **B** —— 纯数字 + 高优徽标（priority 0–2 附加橙色高优 badge，3–9 纯数字），纠"0=最高"反直觉（见 FR-002/FR-009）。
- Q: 是否为本批新增列加排序/筛选交互 → A: **C** —— 加「优先级」筛选器 + 列排序（后端 `WorkflowQuery` 扩 priority 参数、SQL 增 WHERE/ORDER BY；前端增筛选器 + 可排序列头，见 FR-010/FR-011）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 周期任务流看「下次触发时间」 (Priority: P1)

运维打开「周期任务流」卡片，无需点进任一流详情，即可一眼看到每个流"下一次什么时候自动触发"。

**Why this priority**: 周期调度的核心信息就是"下次何时跑"。当前列只有"上次运行"(向后看)，缺"下次触发"(向前看)，是运维巡检/排障的第一诉求。

**Independent Test**: 周期卡片列表多出「下次触发时间」列，值来自 `next_trigger_time`，未排程或回填前显示 —；可独立于其他故事交付。

**Acceptance Scenarios**:

1. **Given** 一个 ONLINE + CRON 且 `next_trigger_time` 在未来约 3 小时的流，**When** 打开周期卡片，**Then**「下次触发时间」列显示"3 小时后"（相对时间）。
2. **Given** 首轮回填前 `next_trigger_time` 为 NULL 的流，**When** 渲染列表，**Then** 显示 —，不报错。

---

### User Story 2 - 两卡片看「优先级」 (Priority: P2)

运维在「周期任务流」和「手动任务流」卡片都能看到每个流的优先级（0–9），用于抢占定位与排障排序。

**Why this priority**: `priority` 已在 DTO 下发但前端未展示；展示它零后端成本，且优先级/软抢占是 Weft 调度内核既有概念。

**Independent Test**: 两卡片各多出「优先级」列，值为 0–9 整数；不依赖其他故事。

**Acceptance Scenarios**:

1. **Given** `priority=3` 的 ONLINE 流，**When** 渲染列表，**Then**「优先级」列显示纯数字 3（无徽标）。
2. **Given** `priority=1` 的高优流，**When** 渲染，**Then**「优先级」列显示 1 + 橙色高优徽标。
3. **Given** `priority` 为 NULL 的旧数据，**When** 渲染，**Then** 显示 —（schema DEFAULT 5，新数据不触发）。

---

### User Story 3 - 名称下方看描述副标题 (Priority: P3)

运维在两卡片扫视列表时，每个任务流名称下方直接附带一行描述，无需点开就能分辨"这个流是干嘛的"。

**Why this priority**: `description` 已在 DTO 下发但未展示；作名称副标题不占独立列、提升信息密度，是 C 方案相对 B 的增量。优先级低于"下次触发""优先级"两个核心列。

**Independent Test**: 名称列下方出现描述副标题；描述为空时只显示名称、不预留空行。

**Acceptance Scenarios**:

1. **Given** 有非空 description 的流，**When** 渲染，**Then** 名称下方一行显示描述（超长截断 + tooltip）。
2. **Given** description 为 NULL/空串的流，**When** 渲染，**Then** 仅显示名称，无空副标题行。

---

### User Story 4 - 按优先级筛选与排序 (Priority: P2)

运维在两卡片能按"高优/普通"筛选任务流，并点击「优先级」列头切换升/降序，把高优流或低优流排到顶部，快速定位抢占候选或排查低优积压。

**Why this priority**: 补了优先级列后，筛选+排序是其价值放大器；server 分页框架已具备扩展条件。与"看优先级"(US2) 同级重要，但依赖 US2 先落地。

**Independent Test**: 卡片筛选栏出现「优先级」分段（高优/普通/全部）；「优先级」列头可点击排序，结果按 server 端 priority 重排刷新。

**Acceptance Scenarios**:

1. **Given** 混合优先级的列表，**When** 选「高优」筛选，**Then** 仅显示 priority 0–2 的流。
2. **Given** 列表，**When** 点击「优先级」列头切降序，**Then** 按 priority 从大到小重排（server 端），NULL 行置末。

---

### Edge Cases

- `next_trigger_time` 为 NULL（首轮回填前 / 手动流）：列显示 —，不报错。
- `priority` 为 NULL（历史数据）：显示 —，不阻塞渲染。
- 列数增加后 `widthPct` 总和须重分配到 100，避免横向溢出；列在窄屏过多时是否可隐藏次要列待定（见 Assumptions）。
- 字段选型若用户选择 C 类（高成本 join），范围需重新评估——默认排除。
- 下次触发已过期（`next_trigger_time` < now，调度延迟未及时触发）：相对时间显示"已过期 Nm/Nh"，提醒运维关注调度滞后。
- 下次触发距今很远（如 >7 天）：相对时间退化为"N 天后"，避免"N 小时后"数字过大不可读。
- 排序时 `priority` 为 NULL 的行：SQL `NULLS LAST` 置末，不丢失。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 「周期任务流」列表 MUST 新增「下次触发时间」列，值取自 `workflow_def.next_trigger_time`，以**相对时间**展示（如"3 小时后"/"已过期 2m"）；未排程/未回填显示 —。
- **FR-002**: 「周期任务流」与「手动任务流」列表 MUST 新增「优先级」列，值取自 `workflow_def.priority`（0–9，0=最高）；展示为纯数字，高优（0–2）附加橙色高优徽标提示。
- **FR-003**: 新增列后，两卡片列宽（`widthPct`）MUST 重新分配使总和=100，且不得丢失任何现有列。
- **FR-004**: 后端 `WorkflowListRow` DTO 与列表投影 SQL MUST 增加 `next_trigger_time` 字段（周期相关；手动/依赖流透传 NULL）。
- **FR-005**: 所有新增列 MUST 有中英双语 i18n key（`ops` 命名空间），两 bundle key 集一致（CI 可检）。
- **FR-006**: 补充字段集确认为 **C 方案**：周期=下次触发时间+优先级+描述副标题；手动=优先级+描述副标题（见 `## Clarifications`）。
- **FR-007**: 「任务流名称」列 MUST 在名称下方以副标题形式展示 `description`；描述为空时不显示副标题、不占独立列。
- **FR-008**: 相对时间展示 MUST 覆盖三态——未来（"N 小时后"/"N 分钟后"/"N 天后"）、已过期（`next_trigger_time` < now 时显示"已过期 Nm/Nh"）、临近（约 1 分钟内显示"即将"）；需提供中英双语 i18n 文案，新增或复用前端相对时间 util。
- **FR-009**: 高优徽标阈值定为 `priority ≤ 2`；徽标文案需中英双语 i18n（如"高优"/"High"），样式遵循 shadcn 语义 token（amber/warning 系），不手写 `dark:` 覆盖。
- **FR-010**: 后端 `WorkflowQuery` MUST 新增可选 `priority` 筛选参数；列表 SQL 的 WHERE 与 ORDER BY MUST 支持按 `priority` 过滤与排序（server 端排序，与现有 server 分页一致）。
- **FR-011**: 前端两卡片 MUST 新增「优先级」筛选器（复用 `FilterDef` 分段形态：高优 0–2 / 普通 3–9 / 全部），且「优先级」列头 MUST 支持点击切换升/降序。

### Key Entities *(include if feature involves data)*

- **workflow_def**: 任务流定义，本 feature 主数据源。本 feature 启用的两个关键"未用"字段：`next_trigger_time`（下次计划触发，单值持久化，cron 扫描依据）、`priority`（0–9 工作流优先级，0=最高）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 运维无需点进任一流详情，即可在周期列表看到每个流的下次触发时间。
- **SC-002**: 运维可在周期/手动两列表按优先级快速定位高优或低优流。
- **SC-003**: 新增列后两卡片在标准视口下无横向滚动、列宽视觉均衡。
- **SC-004**: 每个新增列都能追溯到 `workflow_def` 的某个字段与一个具体运维场景（无"为加而加"的列）。

## Assumptions

- 字段集已确认为 **C 方案**：周期补「下次触发时间 + 优先级 + 描述副标题」、手动补「优先级 + 描述副标题」。
- `timeout_sec` 暂不单列，避免周期流列数过多；如运维排查需要可后续追加为独立列。
- C 类高成本 join 字段（负责人名字 / 类目路径 / 节点任务数）不在本 spec 范围，拆为后续 feature（与 038「实例列表」将负责人字段同样拆后续的处理一致）。
- `next_trigger_time` 直接取 `workflow_def` 列，不回填历史 NULL（首轮回填机制已存在于调度内核，老数据留 NULL 显示 —）。
- 复用现有 `WorkflowListRow` DTO 与 server 分页 SQL，仅增量加字段，不重构投影结构（遵循 Constitution 原则 V「内核复用」）。
- 增强方向契合 Constitution 原则 IV——"拆除不得损伤运行态观测"，本 feature 是对 ops 列表观测的**增强**而非削弱。
- 排序走 server 端（与现有 server 分页一致），`priority` NULL 行用 `NULLS LAST` 兜底；优先级筛选器复用现有 `FilterDef` 分段交互（高优 0–2 / 普通 3–9 / 全部）。
