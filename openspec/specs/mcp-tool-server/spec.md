# mcp-tool-server Specification

## Purpose

定义 DataWeave MCP 工具服务器能力：以符合 MCP Streamable HTTP 规范的端点向 workhorse-agent 暴露平台工具，经 Bearer 认证保护，通过工具注册中心作为平台能力唯一真相源，查询直通领域服务、写操作经 PolicyEngine 闸门，并对超长输出统一截断与归档。

## Requirements

### Requirement: MCP Streamable HTTP 端点
`dataweave-api` SHALL 暴露符合 MCP Streamable HTTP 规范的端点（POST JSON-RPC + GET SSE），至少支持 `initialize`、`tools/list`、`tools/call` 方法，供 workhorse-agent 作为 MCP host 接入。

#### Scenario: 工具列表可发现
- **WHEN** MCP 客户端完成 initialize 后调用 `tools/list`
- **THEN** 返回全部已注册平台工具，每个工具含 name、description、JSON Schema 形参定义

#### Scenario: 工具调用返回结构化结果
- **WHEN** 客户端调用 `tools/call` 执行某只读工具且参数合法
- **THEN** 返回工具结果内容；参数不合法时返回 JSON-RPC 错误而非 500

### Requirement: Bearer 认证
MCP 端点 SHALL 校验 `Authorization: Bearer <token>`，token 经配置注入；缺失或错误的请求 MUST 被拒绝（401）。

#### Scenario: 无 token 拒绝
- **WHEN** 请求未携带或携带错误 Bearer token
- **THEN** 返回 401，不执行任何工具逻辑

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

### Requirement: 工具输出截断约定
工具输出超过配置阈值时 SHALL 截断并附截断标记（含原始长度），完整输出按引用存档供审计回放。

#### Scenario: 大输出截断
- **WHEN** 工具结果超过阈值
- **THEN** 返回给 agent 的内容带 `[truncated]` 标记与原始大小，审计记录保存完整输出引用
