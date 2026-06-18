# text-to-sql Specification

## Purpose

定义自然语言转 SQL 能力（MVP 为规则 mock，经 `LlmClient` 接口抽象便于替换为真实模型）：将预置中文问法识别为 SQL、执行后返回 SQL 文本与表格结果，未命中时优雅降级，并约束只执行只读（SELECT）语句。

## Requirements

### Requirement: 自然语言转 SQL（mock）

Agent 引擎 SHALL 将预置的中文问法识别为对应的 SQL，执行后返回 SQL 文本与表格结果。MVP 阶段为规则 mock，但 SHALL 通过 `LlmClient` 接口抽象，便于后期替换为真实模型。

#### Scenario: 命中预置问法返回 SQL 与结果
- **WHEN** 用户提问命中预置问法（如「查一下 orders 表有多少条」）
- **THEN** Agent 返回对应 SQL（如 `select count(*) from orders`）
- **AND** 在 H2/PostgreSQL 上执行该 SQL，并把结果以表格结构回传

#### Scenario: 未命中问法时优雅降级
- **WHEN** 用户提问未命中任何预置问法且未识别为指标/任务/血缘意图
- **THEN** Agent 返回一条说明性文本，提示当前 mock 引擎支持的问法范围，不抛错

### Requirement: SQL 安全边界

mock 引擎 SHALL 仅执行只读查询（SELECT）类语句。

#### Scenario: 拒绝非只读语句
- **WHEN** 生成或解析出的 SQL 含 `INSERT/UPDATE/DELETE/DROP` 等写操作
- **THEN** 引擎拒绝执行并返回说明，不改动数据
