# execution-environment Specification

## Purpose
为运行实例引入「运行环境」维度（`env`：`DEV`/`PROD`），在触发入口判定并落值，用于实例列表区分与运维统计口径。当前阶段为逻辑语义标签，不驱动物理隔离。

## Requirements
### Requirement: 实例携带运行环境维度

系统 SHALL 在 `workflow_instance` 与 `task_instance` 上记录运行环境 `env`（取值 `DEV` / `PROD`），列默认 `PROD`。`env` 在触发入口判定并落值，MUST NOT 在运行期推断。落值规则：CRON 周期触发与正式手动运行（`run_mode=NORMAL`）落 `PROD`；画布试跑与 `run_mode=TEST`（含 `triggerTestRun`）落 `DEV`。

本能力当前阶段中 `env` 为**逻辑语义标签**，用于实例列表区分与运维统计口径，MUST NOT 驱动 datasource 选择或调度分区（物理隔离留后续 change）。

#### Scenario: 周期触发实例落 PROD
- **WHEN** 调度器按 CRON 触发一个工作流运行
- **THEN** 新建的 `workflow_instance` 与各 `task_instance` 的 `env` 为 `PROD`

#### Scenario: 试跑实例落 DEV
- **WHEN** 用户在画布对任务发起试跑（`run_mode=TEST`）
- **THEN** 新建的 `task_instance` 的 `env` 为 `DEV`

#### Scenario: 历史实例默认 PROD
- **WHEN** schema 升级后读取升级前已存在的实例
- **THEN** 其 `env` 为列默认值 `PROD`，语义不变

### Requirement: 环境维度向后兼容且不破坏现有行为

系统 SHALL 保证 `env` 列的引入为非破坏性：缺省 `PROD`，既有触发/调度/统计路径在不显式处理 `env` 时行为不变。`env` 与 `run_mode`（NORMAL/TEST）为正交轴——`run_mode` 表达"是否计统计/跑草稿"，`env` 表达"运行环境"。

#### Scenario: 未显式传 env 的路径默认 PROD
- **WHEN** 某触发路径未显式设置 `env`
- **THEN** 实例 `env` 取列默认 `PROD`，调度与统计行为与引入前一致
