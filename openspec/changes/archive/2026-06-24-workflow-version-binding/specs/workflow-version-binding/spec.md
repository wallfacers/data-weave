## ADDED Requirements

### Requirement: 触发器以已发布快照为唯一真相物化

系统 SHALL 在触发周期（CRON）或正式手动（`run_mode=NORMAL`）的工作流运行时，从该工作流 `current_version_no` 对应的 `workflow_def_version.dag_snapshot_json` 物化运行实例：节点拓扑（`SnapshotNode`）、各节点 `task_id` 与 `task_version_no` MUST 取自快照，**不得**读取 live `workflow_node` 拓扑或 live `task_def.current_version_no`。`workflow_instance.workflow_version_no` MUST 等于实际所物化的快照版本号（名副其实）。VIRTUAL 节点判定沿用快照节点 `node_type`。

快照只存稳定的 `nodeKey`；物化 `task_instance.workflow_node_id` 时系统 MUST 按 `(workflowId, nodeKey)` 查 live `workflow_node` 取物理主键 id，但拓扑与版本仍以快照为准。

#### Scenario: 周期运行从快照物化钉死版本
- **WHEN** 一个已上线工作流被 CRON 触发，其快照将节点 A 钉为 task 版本 v3、节点 B 钉为 v2
- **THEN** 系统创建的 `task_instance` 的 `task_version_no` 分别为 v3、v2，且 `workflow_instance.workflow_version_no` 等于该快照版本号

#### Scenario: live DAG 草稿改动不影响周期运行
- **WHEN** 用户在画布上给已上线工作流新增了一个节点但未重新发布，随后该工作流被 CRON 触发
- **THEN** 系统按已发布快照物化（不含新增节点），新增节点不进入本次运行

#### Scenario: 任务发了新版但工作流未重新晋级时仍跑旧钉死版本
- **WHEN** 节点 A 的任务被重新发布（`current_version_no` v3→v4），工作流未重新晋级，随后被触发
- **THEN** 系统仍按快照钉死的 v3 物化节点 A，不自动采用 v4

### Requirement: 无快照工作流回退兼容

系统 SHALL 在工作流无已发布快照（`current_version_no=0` 或无对应 `workflow_def_version`）时回退为现状的 live 物化行为，避免无快照可跑。正式上线（`ONLINE`）工作流必有快照，走规定性路径。

#### Scenario: 未发布工作流触发回退 live 物化
- **WHEN** 一个从未发布的工作流被触发运行
- **THEN** 系统按 live `workflow_node` 与 live 任务版本物化（兼容现状），不因缺快照失败

### Requirement: 快照节点在 live 已删时的处理

系统 SHALL 在快照引用的 `nodeKey` 在 live `workflow_node` 中已不存在（发布后又删节点未重新晋级）时跳过该节点物化并告警，MUST NOT 静默丢弃；该工作流 SHALL 被判定为 DAG 草稿漂移，提示用户重新晋级。

#### Scenario: 快照节点 live 已删
- **WHEN** 触发时快照含 nodeKey `n1` 但 live 已无该节点
- **THEN** 系统跳过 `n1` 的物化并记录告警，且该工作流在读侧被标记需要重新晋级

### Requirement: 工作流漂移检测

系统 SHALL 提供工作流「漂移」读侧计算（不落库），在工作流详情/列表读取时计算。工作流 SHALL 被判定为「需要重新晋级」当且仅当满足任一：(a) **任务版本漂移**——当前快照中任一 `SnapshotNode.taskVersion` 小于该 task 的当前 `current_version_no`；(b) **DAG 草稿漂移**——`workflow_def.has_draft_change=1`。漂移结果 MUST 暴露给前端用于展示，列表页 MAY 仅返回布尔，详情页 SHALL 可返回漂移节点明细（`nodeKey`、`pinned`、`latest`）。

#### Scenario: 任务发新版后工作流漂移
- **WHEN** 工作流快照将节点 A 钉为 v3，而节点 A 的任务当前 `current_version_no` 为 v4
- **THEN** 读取该工作流时漂移结果为 drifted=true，明细含 `{nodeKey: A, pinned: 3, latest: 4}`

#### Scenario: 无任何更新时不漂移
- **WHEN** 工作流所有节点快照版本均等于各任务最新发布版且 `has_draft_change=0`
- **THEN** 读取该工作流时漂移结果为 drifted=false

#### Scenario: DAG 草稿改动构成漂移
- **WHEN** 工作流 `has_draft_change=1`（live DAG 改了未发布）
- **THEN** 读取时漂移结果为 drifted=true

### Requirement: 重新晋级重建快照

系统 SHALL 提供「重新晋级到最新」动作，复用工作流发布端点（`POST /api/workflows/{id}/publish`）：从各任务**最新** `current_version_no` + 当前 live DAG 重新冻结 `dag_snapshot_json`、校验无环、`current_version_no++`、`has_draft_change=0`。重新晋级是 UI 编辑类操作（与 `saveDag`/`update`/`offline`/`publish` 同类），与 rollback/run 等产生运行副作用的操作不同，沿用发布的非闸门直执行路径；审计由新建的 `workflow_def_version` 行天然留痕（每次晋级一条版本记录）。

#### Scenario: 重新晋级采纳任务新版
- **WHEN** 用户对漂移工作流（节点 A pinned v3 / latest v4）执行重新晋级
- **THEN** 系统生成新快照将节点 A 钉为 v4，`current_version_no` 自增，漂移消除

#### Scenario: 重新晋级留版本审计
- **WHEN** 用户触发重新晋级
- **THEN** 系统新建一条 `workflow_def_version`（含新快照与 `published_at`），构成晋级审计轨

### Requirement: 运行方式对快照真相源的遵守

系统 SHALL 按运行方式区分是否走快照：周期（CRON）与正式手动 FULL/子图 MUST 从快照物化（钉死版本）；试跑（`run_mode=TEST`）MUST NOT 走快照，仍跑草稿内容（`content_override`/`task_version_no=null`）；手动 ONLY_NODE/单任务运行沿用 `triggerManualTaskRun`（跑任务 `current_version_no`），无工作流快照语境。

#### Scenario: 试跑不走快照跑草稿
- **WHEN** 用户对工作流节点发起试跑（`run_mode=TEST`）
- **THEN** 系统跑该任务的草稿内容（`task_version_no=null`），不从工作流快照取版本
