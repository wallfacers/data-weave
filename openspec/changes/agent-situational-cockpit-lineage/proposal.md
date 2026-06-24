## Why

DataWeave 当前的差异化不足：自然语言建任务已是行业桌面赌注，现有驾驶舱（`cockpit`）只是「一堆统计卡 + 失败表」，本质仍是传统中台仪表盘。真正能拉开差距、且让**非数据开发人员也能用**的能力缺位——用户打开应该一眼看见「整个系统的数据正怎样流动、到哪一步了、同步了多少、还要多久」，而这要求一张**跨系统的活血缘图**。但探查发现这张图的脊柱根本不存在：现有血缘仅指标级（`metric_lineage`），且 Agent 建任务时对输入/输出表**一无所知**（`create_task` 仅接 `name/content/cron`，`datasource_id` 落库为 NULL）。

机会点在于：传统平台的表级血缘只能**事后解析存量 SQL**（脏、滞后、SHELL 任务抓瞎），而 DataWeave 的 SQL 是 **Agent 亲手写的**——让 Agent 在产出任务时把「读哪些表、写哪些表」作为结构化输出一并交出，血缘就成了施工竣工图而非考古。这是 Agent-native 平台独有、别人结构上抄不动的点，配合现有 `PolicyEngine` 全控制，才真正闭环「Agent 控制数据全生命周期」。

## What Changes

- **新增表级/任务级血缘模型**：以「表为节点、任务为边」的二部图，跨数据源记录 `表 A → 任务 X → 表 B`。分两层——**设计态**（建任务即生成拓扑骨架）与**运行态**（每次跑批观测实际读写行数/字节）。
- **血缘三来源 + 可信度标签**：设计态边来源为 `AGENT`（Agent 声明，主）/ `SQL_PARSED`（解析 `content` 自动校验）/ `FORM`（表单兜底）；A×B 不一致时打「待复核」标签——把「写操作无旁路必过闸」的全控制哲学延伸到血缘可信度。
- **`create_task` 演进**：MCP 工具与建任务链路新增 `datasourceId / targetDatasourceId` 与 `reads[] / writes[]`（输入/输出表）声明，落库填充血缘；workhorse 真脑模式天然产出结构化 io，mock 模式降级到 SQL 解析。
- **运行态采集**：worker 执行后上报本次读/写行数与字节，落 `task_run_table_io`，支撑「今日同步 N 亿行」与节点吞吐。
- **新增态势驾驶舱主舞台「活血缘图」视图**：跨系统血缘图作为第一屏中心，节点按 `ODS/DWD/DWS/ADS` 分层布局，SSE 实时变色（复用 `realtime-streams`），节点贴 ETA 与根因诊断一句话；右栏「Agent 举手台」复用 `self-diagnosis`；点节点下钻到现有日志/指标/DAG 详情视图。
- **节点 ETA 标注**：基于历史运行时长中位数（复用 `SlaService` 基线）+ 当前已耗时，给出「还要多久」。
- **驾驶舱第一屏重心迁移**：`cockpit` 视图从「统计卡拼盘」演进为「活血缘图为中心 + 顶条聚合数 + 右栏举手台」，统计卡降为顶条。

分期落地：**Phase 0** 用现有零件搭外壳（顶条聚合 + 举手台 + 主舞台先并排现有单工作流 DAG）；**Phase 1** 长出血缘脊柱（设计态二部图 + Agent 声明 + SQL 解析校验，主舞台换真·跨系统活血缘图）；**Phase 2** 接大脑（运行态行数上报 + ETA 标注到节点）。

## Capabilities

### New Capabilities
- `table-lineage`: 表级/任务级二部图血缘——设计态（建任务即生成）+ 运行态（跑批观测行数）两层模型，三来源（AGENT/SQL_PARSED/FORM）+ 可信度标签，跨数据源 `表→任务→表` 追溯与上下游双向查询。
- `lineage-cockpit`: 态势驾驶舱主舞台活血缘图——跨系统血缘可视化第一屏，分层布局 + SSE 实时变色 + 节点 ETA/根因标注 + Agent 举手台 + 点节点下钻，面向非开发人员的「打开即懂」入口。

### Modified Capabilities
- `cockpit-shell`: 驾驶舱第一屏重心从「统计卡拼盘」迁移为「活血缘图为中心」，统计卡降为顶条聚合；现有失败表/诊断区块整合进右栏举手台与节点下钻。
- `mcp-tool-server`: `create_task` 工具 schema 新增数据源与输入/输出表声明参数，建任务时落库血缘。

## Impact

- **后端 `dataweave-master`**：新增 `table-lineage` 领域（`data_table` / `task_table_io` / `task_run_table_io` 表 + 服务）；`TaskService.createAndOnline` 与 `TaskDef` 落库链路扩展 io；`SlaService` 暴露 ETA 预测；不动 `metric_lineage`（指标域保持）。
- **后端 `dataweave-api`**：`McpToolRegistry.create_task` schema 扩展并经闸门；新增血缘查询 REST（跨系统图 + 上下游）；驾驶舱聚合端点（健康度/今日同步量）。
- **后端 `dataweave-worker`**：执行后采集读/写行数（JDBC `updateCount` / 包裹 count），上报运行态血缘。
- **前端**：新增 `lineage-cockpit` 视图（复用 `@xyflow/react`、`useEventSource`、现有下钻视图与 `dataweave.ui.open` 召唤）；`cockpit` 视图重构；建任务表单/MCP 入口补 io 字段。
- **数据库**：新增 3 张表 + `task_def` 可选 io 衍生字段；H2/PG 双方言 DDL。
- **依赖**：SQL 解析需引入解析库（如 JSQLParser）于 master/worker；Agent 声明 io 依赖 workhorse 真脑模式（mock 降级）。
- **不破坏**：现有 `cockpit`/`data-lineage`/`metric_lineage`/调度内核均向后兼容，血缘缺失时图降级到数据源级粗粒度。
