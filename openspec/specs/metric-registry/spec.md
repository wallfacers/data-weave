# metric-registry Specification

## Purpose

定义指标注册与按名溯源能力：注册指标（名称、口径 SQL、来源表、维度、负责人与版本），口径不可篡改（改口径生成新版本），并支持 Agent 在对话中识别指标名、返回口径数据与口径溯源。

## Requirements

### Requirement: 指标注册

系统 SHALL 支持注册指标，记录名称、口径 SQL、来源表、维度、负责人与版本。指标口径 SHALL 不可篡改：修改口径时生成新版本而非覆盖旧版本。

#### Scenario: 注册一个指标
- **WHEN** 注册名为「GMV」、口径 `sum(order_amount)`、来源表 `orders` 的指标
- **THEN** `metrics` 表新增一条记录，版本号为 1
- **AND** 系统种子已预置该 GMV 指标

#### Scenario: 修改口径生成新版本
- **WHEN** 对已存在的指标提交新的口径 SQL
- **THEN** 系统新增一条版本号递增的记录，旧版本记录保持不变

### Requirement: 按名查询指标并溯源

Agent SHALL 在对话中识别指标名，返回该指标的口径数据，并附带口径溯源（口径 SQL 与来源表）。

#### Scenario: 查询 GMV 返回数据与口径
- **WHEN** 用户提问「GMV 是多少」
- **THEN** Agent 识别出 GMV 指标，执行其口径 SQL 并返回数值
- **AND** 回复中包含口径说明（`sum(order_amount)`，来源表 `orders`）
