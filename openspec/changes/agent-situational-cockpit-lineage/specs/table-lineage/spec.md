## ADDED Requirements

### Requirement: 表-任务二部图血缘模型

系统 SHALL 以「表为节点、任务为边」的二部图记录跨数据源血缘，节点存于 `data_table`（唯一键 `datasource_id + qualified_name`，含可空 `layer` ∈ {ODS, DWD, DWS, ADS}），边存于 `task_table_io`（`task_def_id × table_id × direction`，`direction` ∈ {READ, WRITE}）。该模型 MUST 独立于指标域 `metric_lineage`，二者并存互不污染。

#### Scenario: 任务读写多表生成二部图边

- **WHEN** 一个任务从 `ods_order`、`ods_user` 读取并写入 `dwd_order`
- **THEN** `task_table_io` 新增 2 条 READ 边（指向 `ods_order`/`ods_user`）与 1 条 WRITE 边（指向 `dwd_order`）
- **AND** `data_table` 中不存在的表节点按 `datasource_id + qualified_name` 自动创建

#### Scenario: 跨数据源血缘可追溯

- **WHEN** 任务 X 从数据源 A 的表读、写入数据源 B 的表
- **THEN** 血缘边正确关联两个不同 `datasource_id` 的 `data_table` 节点，形成跨源 `表A → 任务X → 表B` 链路

### Requirement: 设计态血缘建任务即生成

系统 SHALL 在任务创建/上线时（任务尚未运行前）即写入设计态血缘边，使全局血缘图无需等待任务执行即可呈现。建任务事务 MUST 同时持久化任务定义与其 `task_table_io` 边。

#### Scenario: 建任务即有血缘

- **WHEN** Agent 创建并上线一个声明了输入/输出表的任务
- **THEN** 在该任务首次运行之前，查询血缘图已能返回该任务的读/写表边

#### Scenario: 无 io 信息降级到数据源级

- **WHEN** 建任务未提供表级 io、仅有 `datasource_id`/`target_datasource_id`
- **THEN** 系统以数据源级粗粒度节点降级记录血缘，不报错且不阻断建任务

### Requirement: 设计态血缘三来源与可信度标签

每条设计态血缘边 SHALL 标注来源 `source` ∈ {AGENT, SQL_PARSED, FORM} 与可信度 `confidence` ∈ {CONFIRMED, UNVERIFIED, CONFLICT}。当任务类型为 SQL 时，系统 MUST 解析 `content` 提取读写表，与 AGENT 声明做交叉校验：一致判 `CONFIRMED`，仅声明未证实判 `UNVERIFIED`，声明与解析矛盾判 `CONFLICT`。

#### Scenario: Agent 声明与 SQL 解析一致

- **WHEN** Agent 声明写 `dwd_order` 且 SQL 解析的 `INSERT INTO` 也是 `dwd_order`
- **THEN** 该 WRITE 边 `source=AGENT`、`confidence=CONFIRMED`

#### Scenario: Agent 声明与 SQL 解析矛盾标记待复核

- **WHEN** Agent 声明写 `dwd_order` 但 SQL 解析未发现该写入目标
- **THEN** 该边 `confidence=CONFLICT`，并可被驾驶舱「待复核」清单查出

#### Scenario: 非 SQL 任务仅凭声明记录

- **WHEN** 一个 SHELL 任务由 Agent 声明读写表但无法做 SQL 解析
- **THEN** 边以 `source=AGENT`、`confidence=UNVERIFIED` 记录，不因无法解析而丢弃

#### Scenario: mock 模式降级以解析为主

- **WHEN** 系统处于 `agent.mode=mock`（无结构化脑，无法产出 AGENT 声明）
- **THEN** SQL 任务的血缘以 `SQL_PARSED` 为主来源记录；非 SQL 任务退化到数据源级

### Requirement: 运行态血缘与同步行数采集

系统 SHALL 在任务执行后采集本次实际读/写行数与字节，落 `task_run_table_io`（`task_instance_id × table_id × direction × row_count × bytes × biz_date`）。行数 MUST 来自 worker 执行的真实 `updateCount` 等执行副作用；无法可靠采集时留空而非估算。

#### Scenario: SQL 写入行数上报

- **WHEN** 一个 `INSERT INTO dwd_order SELECT ...` 任务执行完成，影响 8000 万行
- **THEN** `task_run_table_io` 记录该 WRITE 边 `row_count=80000000`，关联本次 `task_instance_id` 与 `biz_date`

#### Scenario: 无法采集时留空

- **WHEN** 任务为复杂多语句或非 INSERT 类，worker 拿不到可靠行数
- **THEN** `row_count` 留空，不写入猜测值，「今日同步量」聚合按「已采集任务」口径计算

### Requirement: 血缘上下游双向查询

系统 SHALL 提供以某表或某任务为中心的上下游血缘查询，支持「这张表由谁产出、被谁消费」与「改这张表会影响下游什么」的双向遍历，并支持 N 跳邻域子图拉取以控制全局图规模。

#### Scenario: 反向查询表的产出方

- **WHEN** 查询 `dwd_order` 的上游
- **THEN** 返回写入该表的任务及其再上游的源表，沿 WRITE/READ 边反向遍历

#### Scenario: 影响分析查询下游

- **WHEN** 查询 `ods_order` 的下游影响
- **THEN** 返回读取该表的任务及其写出的下游表，形成影响链路

#### Scenario: N 跳邻域子图

- **WHEN** 请求以某节点为中心、深度 2 跳的子图
- **THEN** 仅返回该邻域内的节点与边，而非全图，供前端按需渲染
