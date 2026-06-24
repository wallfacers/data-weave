# cross-cycle-dependency Specification

## Purpose
TBD - created by archiving change workflow-dependency-modes. Update Purpose after archive.
## Requirements
### Requirement: 跨周期依赖语义

工作流节点 SHALL 支持跨周期依赖：本节点本次（本 `biz_date`）实例的就绪，可额外要求某节点**上一周期**实例（`biz_date` 按 `date_offset` 偏移）到达 `SUCCESS`，与同周期 DAG 依赖正交。跨周期依赖以现成的 `workflow_dependency` 表表达，字段含：`node_id`（下游本节点）、`depend_workflow_id`（被依赖工作流）、`depend_node_id`（被依赖节点）、`date_offset`（`CURRENT_DAY`=同日不偏移、`LAST_DAY`=上一日）、`dep_type`（默认 `ALL_SUCCESS`，预留扩展）、`enabled`（默认启用）。

#### Scenario: 跨周期依赖与同周期依赖正交叠加

- **WHEN** 一个节点既有同周期上游边 A，又配置了跨周期自依赖（LAST_DAY）
- **THEN** 该节点就绪需同时满足：同周期 A 本周期 `SUCCESS` 且自身上一日实例 `SUCCESS`

#### Scenario: 两种依赖语义不被混用

- **WHEN** 用户在画布上配置依赖
- **THEN** 同周期上下游关系落在 `workflow_edge`（带 `strength`），跨周期关系落在 `workflow_dependency`（带 `date_offset`），二者分表表达、互不污染

### Requirement: 自依赖

节点 SHALL 支持自依赖：`workflow_dependency` 中 `depend_workflow_id` = 本工作流、`depend_node_id` = 本节点自身、`date_offset=LAST_DAY`。开启自依赖的节点，本次实例 MUST 等自身上一日实例 `SUCCESS` 才就绪。系统 MUST 接受 `workflow_dependency` 的自指记录（`depend_workflow_id=workflow_id` 且 `depend_node_id=node_id`），不视为环路。

#### Scenario: 自依赖等昨天自身产出

- **WHEN** 日级累加节点 N 开启自依赖（LAST_DAY），N 的昨日实例已 `SUCCESS`
- **THEN** N 的今日实例就绪可运行

#### Scenario: 自指依赖不再被当作环拒绝

- **WHEN** 用户为本节点创建一条自指的 `workflow_dependency`
- **THEN** 系统接受该记录，不再抛 `workflow.graph.self_dependency`；跨工作流全局环检测照常对非自指依赖生效

### Requirement: 依赖上游上一周期

节点 SHALL 支持依赖指定上游节点的上一周期实例：`workflow_dependency` 的 `depend_workflow_id`/`depend_node_id` 指向上游节点、`date_offset=LAST_DAY`。本节点本次实例就绪 MUST 等该上游节点上一日实例 `SUCCESS`。

#### Scenario: 依赖上游昨日产出

- **WHEN** 节点 B 配置依赖上游 A 的 LAST_DAY，A 的昨日实例尚未运行
- **THEN** B 的今日实例不就绪、保持 `WAITING`；A 的昨日实例补跑至 `SUCCESS` 后，B 的今日实例解锁

### Requirement: 最早回溯时间（首周期豁免）

每条跨周期依赖 SHALL 携带 `earliest_biz_date`（可空）。`biz_date < earliest_biz_date` 的实例 MUST 跳过该跨周期检查、直接可运行（首周期豁免，避免首周期无上一周期可等而永久 `WAITING` 死锁）；`biz_date >= earliest_biz_date` 才执行上一周期检查。`earliest_biz_date` 为空 MUST 等价于「该跨周期依赖不启用」（安全降级，保证旧行为零回归）。

#### Scenario: 首周期豁免直接运行

- **WHEN** 节点 N 自依赖 `earliest_biz_date=2026-06-20`，当前 `biz_date=2026-06-20`
- **THEN** N 的该实例跳过上一周期检查，直接进入可运行

#### Scenario: 回溯起点之后恢复检查

- **WHEN** 节点 N 自依赖 `earliest_biz_date=2026-06-20`，当前 `biz_date=2026-06-21`，N 的 `2026-06-20` 实例已 `SUCCESS`
- **THEN** N 的 `2026-06-21` 实例通过上一周期检查、就绪可运行

#### Scenario: earliest 为空安全降级

- **WHEN** 一条跨周期依赖的 `earliest_biz_date` 为空
- **THEN** 该依赖不产生任何就绪阻塞，节点行为与无跨周期依赖一致

### Requirement: 运行方式对跨周期依赖的遵守

跨周期依赖的就绪检查 SHALL 仅对周期触发（`trigger_type='CRON'`）的工作流实例生效。测试运行（`run_mode='TEST'` 的孤立实例）与手动触发（`trigger_type='MANUAL'`，含 `FULL`/`TO_NODE`/`DOWNSTREAM`/`ONLY_NODE`）的实例 MUST 忽略跨周期依赖——即席验证不被「等昨天」阻塞。

#### Scenario: 周期运行遵守跨周期

- **WHEN** 一个开启自依赖的工作流被 cron 触发，`biz_date` 为今日
- **THEN** 实例节点就绪需等自身昨日实例 `SUCCESS`（受 `earliest_biz_date` 豁免约束）

#### Scenario: 手动运行忽略跨周期

- **WHEN** 用户对一个开启自依赖的工作流发起手动运行（FULL/TO_NODE/DOWNSTREAM）
- **THEN** 实例不检查跨周期依赖，节点仅按同周期依赖就绪

#### Scenario: 测试运行忽略跨周期

- **WHEN** 用户对一个开启自依赖的节点发起测试运行（`run_mode=TEST`）
- **THEN** 孤立 TEST 实例不检查任何依赖，立即可运行

