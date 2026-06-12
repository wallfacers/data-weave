# task-test-run Delta

## ADDED Requirements

### Requirement: 草稿内容测试下发

单个任务 SHALL 支持测试运行：以 `run_mode=TEST` 下发该任务的**当前草稿内容**（而非已发布版本），跳过 DAG 与跨流依赖检查，直接进入调度快路径。测试运行实例 MUST NOT 出现在正式运维统计（实例列表、失败清单、SLA 基线）中。

#### Scenario: 测试运行跑草稿

- **WHEN** 用户修改任务脚本未发布，发起测试运行
- **THEN** worker 执行的是修改后的草稿内容，实例 run_mode=TEST

#### Scenario: 测试实例不污染统计

- **WHEN** 一个 TEST 实例执行失败
- **THEN** 正式失败清单与 SLA 统计不包含该实例

### Requirement: 测试运行闸门分级

测试运行属于人为/Agent 发起的写操作，MUST 经 `GatedActionService`。默认分级 SHALL 为 L1（留 `agent_action` 痕后直接执行，不建审批单）；分级规则 MUST 数据驱动（`policy_rules` 表），允许企业按任务类型收紧（如 TEST+SHELL 抬至 L2 审批）。

#### Scenario: 默认 L1 直执行

- **WHEN** 用户发起一次 SQL 任务测试运行（默认规则）
- **THEN** 留痕后立即下发执行，无审批等待

#### Scenario: 规则收紧后需审批

- **WHEN** 企业在 policy_rules 中将 TEST+SHELL 配置为 L2 后发起 shell 测试运行
- **THEN** 产生审批单，返回 PENDING_APPROVAL，批准后才下发

### Requirement: TEST 预留槽

每个 worker SHALL 预留可配数量的槽位（默认 1，可配 0 关闭）仅供 TEST 实例使用，保证测试运行不被例行任务长期饿死；TEST 实例同时可使用普通空闲槽位。

#### Scenario: 例行任务占满时测试仍可跑

- **WHEN** 全部普通槽位被例行任务占满，用户发起测试运行
- **THEN** 测试实例经预留槽立即执行，无需等待例行任务完成

#### Scenario: 例行任务不占预留槽

- **WHEN** 仅剩 TEST 预留槽空闲时调度例行任务
- **THEN** 例行任务保持 WAITING，不占用预留槽
