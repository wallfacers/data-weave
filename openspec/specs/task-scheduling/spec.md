# task-scheduling Specification

## Purpose

定义自然语言驱动的调度任务能力：Agent 从用户自然语言意图中提取任务定义（名称、类型、执行内容、cron、目标产出）创建任务并配置调度，任务具备状态机（DRAFT → ONLINE），并预置「每日 GMV 统计」种子任务。

## Requirements

### Requirement: 自然语言创建调度任务

Agent SHALL 从用户的自然语言意图中提取任务定义（名称、类型、执行内容、cron 表达式、目标产出），创建任务并配置调度。

#### Scenario: 从一句话建任务
- **WHEN** 用户说「创建一个任务，每天8点执行 `select count(*) from orders`，结果存到 report 表」
- **THEN** 系统在 `tasks` 表新增一条记录，cron 为每天 08:00（`0 0 8 * * ?`），type 为 SQL
- **AND** Agent 回复确认任务已创建，并回显任务名与调度时间

### Requirement: 任务上线与状态机

任务 SHALL 具备状态机（如 DRAFT → ONLINE），新建任务上线后进入可调度状态。MVP 阶段调度执行为 mock 推进。

#### Scenario: 任务上线
- **WHEN** Agent 完成建任务后执行上线动作
- **THEN** 任务状态变为 ONLINE
- **AND** 产生一条 `task_instances` 记录用于演示运行（mock 推进至成功态）

### Requirement: 种子任务

系统种子 SHALL 预置一个「每日 GMV 统计」任务。

#### Scenario: 预置任务存在
- **WHEN** 应用以种子数据启动
- **THEN** `tasks` 表存在「每日 GMV 统计」任务，cron 为 `0 0 8 * * ?`
