## MODIFIED Requirements

### Requirement: 平台工具注册中心
工具注册中心 SHALL 作为平台能力的唯一真相源：M1 至少注册任务/实例/血缘/指标/诊断的查询工具、任务重跑等操作工具、`node_exec`、`approve_and_execute`。工具实现 MUST 复用 master 既有领域服务，不得在工具层重写业务逻辑。`create_task` 工具 schema SHALL 新增可选参数 `datasourceId`、`targetDatasourceId`、`reads[]`、`writes[]`（输入/输出表声明）；当提供时，建任务 MUST 在同一事务内落库数据源关联并写入设计态血缘边（见 `table-lineage`），来源标记为 `AGENT`；未提供时按数据源级降级，向后兼容不报错。

#### Scenario: 查询类工具直通领域服务
- **WHEN** agent 调用 `query_task_instances` 等查询工具
- **THEN** 结果来自 master 领域服务，与观测页 REST 同源同构

#### Scenario: 副作用工具经过 PolicyEngine
- **WHEN** agent 调用任何写操作工具（含 node_exec）
- **THEN** 调用 MUST 先经 PolicyEngine 裁决（见 policy-engine spec），不存在绕过路径

#### Scenario: 建任务声明 io 落库血缘
- **WHEN** agent 调用 `create_task` 并提供 `reads=["ods_order"]`、`writes=["dwd_order"]`
- **THEN** 经 PolicyEngine 裁决放行后，任务定义与对应 `task_table_io` 设计态血缘边（`source=AGENT`）在同一事务内持久化

#### Scenario: 未声明 io 向后兼容
- **WHEN** agent 调用 `create_task` 仅提供 `name/content/cron`，不带 io 参数
- **THEN** 任务正常创建，血缘按数据源级降级或留空，不报错
