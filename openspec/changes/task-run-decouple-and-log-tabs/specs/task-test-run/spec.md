## MODIFIED Requirements

### Requirement: 草稿内容测试下发

单个任务 SHALL 支持测试运行：以 `run_mode=TEST` 下发，跳过 DAG 与跨流依赖检查，直接进入调度快路径。测试运行的**内容来源**为：`POST /api/tasks/{id}/run` 请求体携带的编辑器当前内容（可选字段 `content`/`type`/`paramsJson`）—— 即用户在编辑器中的**最新内容（含尚未保存的改动）**；当请求体未携带内容时，回退为该任务**当前 DB 草稿内容**。测试运行 MUST NOT 强制先保存草稿（不写 `task_def`），下发实例的 `task_version_no=null`。测试运行实例 MUST NOT 出现在正式运维统计（实例列表、失败清单、SLA 基线）中。

#### Scenario: 测试运行跑编辑器未保存内容

- **WHEN** 用户在编辑器修改脚本但未点保存，直接发起测试运行
- **THEN** worker 执行的是请求体携带的编辑器最新内容（含未保存改动），实例 run_mode=TEST，且未触发草稿落库

#### Scenario: 无内容请求体回退 DB 草稿

- **WHEN** 测试运行请求体未携带 content（如从实例列表 rerun 一个历史 TEST）
- **THEN** 系统以该任务当前 DB 草稿内容下发测试运行

#### Scenario: 测试实例不污染统计

- **WHEN** 一个 TEST 实例执行失败
- **THEN** 正式失败清单与 SLA 统计不包含该实例

### Requirement: 测试运行闸门分级

测试运行属于人为/Agent 发起的写操作，MUST 经 `GatedActionService`。请求体携带的临时内容 MUST 作为 `ActionRequest` 的命令快照一并留入 `agent_action` 审计（确保「跑了什么」可回放）。默认分级 SHALL 为 L1（留 `agent_action` 痕后直接执行，不建审批单）；分级规则 MUST 数据驱动（`policy_rules` 表），允许企业按任务类型收紧（如 TEST+SHELL 抬至 L2 审批）。

#### Scenario: 默认 L1 直执行并留痕内容

- **WHEN** 用户发起一次 SQL 任务测试运行（默认规则）
- **THEN** 留痕后立即下发执行，无审批等待，且 agent_action 记录本次下发的内容快照

#### Scenario: 规则收紧后需审批

- **WHEN** 企业在 policy_rules 中将 TEST+SHELL 配置为 L2 后发起 shell 测试运行
- **THEN** 产生审批单，返回 PENDING_APPROVAL，批准后才下发
