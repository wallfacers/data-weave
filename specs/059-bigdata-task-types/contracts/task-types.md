# Contract: 任务类型目录（前端 + 文件契约 + 下发接线）

## T1 — 前端创建入口全类型暴露

- **T1.1** 三处任务类型选择器统一从「SQL/SHELL」扩到全类型集合：
  - `frontend/components/workspace/catalog-tree.tsx`（create-task 对话框，:1115 附近）
  - `frontend/components/workspace/views/workflow-canvas-view.tsx`（画布内建任务，:1194 附近）
  - `frontend/components/workspace/task-config-panel.tsx`（任务配置面板，:120 附近）
- **T1.2** 暴露集合（MVP）：`SQL, SHELL, PYTHON, SPARK, HIVE, FLINK, DATAX, SEATUNNEL`（`ECHO` 保持测试内部用，不暴露）。类型联合类型 `"SQL" | "SHELL"` 须相应放宽。
- **T1.3** 编辑器语言映射 `params-table.tsx` `taskTypeToLang` 增：`HIVE→sql`、`FLINK→sql`、`DATAX→json`、`SEATUNNEL→"hocon"|"text"`（SPARK 已有）。
- **T1.4** i18n：`frontend/messages/{zh-CN,en-US}.json` 补 `ops.nodeDetail.taskType*` 与 `workflowCanvas.taskType*` 键：新增 `taskTypeSpark`（当前缺失）、`taskTypeHive`、`taskTypeFlink`、`taskTypeDataX`、`taskTypeSeaTunnel`。两 bundle key 集合必须一致（CI 校验）。数据术语（DataX/SeaTunnel/Flink/Hive/Spark）保留英文。
- **T1.5** 前端栈门：`DropdownSelect` 自定义 trigger 用 `render`；hugeicons；语义 token；`gap-*`/`size-*`。

## T2 — 文件契约 round-trip

- **T2.1** 新任务类型经 push/pull 全量保真：`type` + 内容体 + `params`（`_flinkMode` 等）+ `datasource`/`targetDatasource` 绑定。
- **T2.2** 不新增 `TaskDoc` 类型化字段；引擎子模式入 `params` map。
- **T2.3** 契约测试：push 一个 `FLINK`(sql)/`DATAX`/`SEATUNNEL`/`HIVE` 任务，pull 到干净目录，断言语义等价（type、content、params、绑定无丢失）。

## T3 — 下发接线（master → gateway → worker）

- **T3.1** `SchedulerKernel`（:317 附近）镜像 `_sparkMode/_jarRef/_mainClass` 提取，增 `_flinkMode` 等引擎子模式提取并带入 `DispatchCommand`。
- **T3.2** `TaskExecutionGateway.DispatchCommand` 增引擎字段（或复用现有 + 新增）。
- **T3.3** `DatasourceResolver.resolve()` 按 type 产出 `ResolvedConnection.engine(...)`（Flink 从绑定数据源 `props_json` / DataX·SeaTunnel 从环境）。
- **T3.4** 两个 gateway 建 `ExecutionContext.EngineSubmitRef`：
  - `InProcessTaskExecutionGateway`（all-in-one，:203 附近 `buildSparkRef` 旁增 `buildEngineRef`）
  - `DistributedTaskExecutionGateway`（over-wire 序列化，:175 附近）
- **T3.5** 本地：`LocalRunMain.selectExecutor` + `buildContext` 增新 type 分支；`LocalRunArgs` 增 `--flink-mode`/`--jar-path`/`--main-class`（DataX/SeaTunnel 无子模式）；`dw` CLI（`cli/`）透传。

## T4 — 血缘（FR-016，SHOULD）

- **T4.1** `HIVE` 与 OLAP `SQL` 任务内容接入现有 `SqlTableExtractor`（Calcite）表/列血缘，与 `SQL` 任务同路径；方言不识别最小降级（不产错血缘）。
- **T4.2** DataX/SeaTunnel/Flink 引擎级血缘为增强项，MVP 可不实现（不产错血缘优先）。

## T5 — 写闸门与审计（Constitution V）

- **T5.1** 新类型任务的 push/运行沿用既有 PolicyEngine 写闸门 + `agent_action` 审计，无旁路。
- **T5.2** 凭据（数据源密码、job 内嵌凭据）不明文入可导出定义/日志。
