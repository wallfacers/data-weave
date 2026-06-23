## Why

当前任务的「编辑」与「运行」被「发布」这道门隔成互斥两态：`TaskService.update` 只允许 `DRAFT` 编辑，`POST /api/tasks/{id}/run` 又硬卡 `ONLINE`（未发布即 409 `task.not_published`）。结果是要调一段脚本必须「改→发布→发现要再改→下线→改→再发布」反复绕圈，发布（上线纳入调度，有副作用）被错误地当成了「能不能运行」的前提。同时 SQL/ECHO 任务在 all-in-one 模式下被模拟成功、零日志（"已结束 / 无日志记录"），运行日志只有写死的单面板、命名不可读，开发-调试闭环体验差。

## What Changes

- **解绑「发布」与「运行」**：`POST /api/tasks/{id}/run` 不再对未发布任务直接拒绝。未发布/草稿 → 起 `run_mode=TEST` 测试运行；已发布 → 起 `run_mode=NORMAL` 正式运行（沿用现有口径）。TEST 与 NORMAL 均经 `GatedActionService` 闸门（默认 L1 直执行 + `agent_action` 留痕）。
- **测试运行跑「编辑器当前内容」**：`/run` 接口新增可选请求体 `{ content, type, paramsJson }`；TEST 运行用前端传入的编辑器最新内容（含未保存改动）下发，不强制先落库。后端 `triggerTestRun` 扩展为可接收临时内容（version=null，不写 `task_def`）。
- **SQL/ECHO 接真实执行器（方案 A）**：新增 `EchoTaskExecutor` 与 `SqlTaskExecutor`。SQL 按 `task.datasourceId` 连业务数据源真跑；**未配置可用数据源时回退到「模拟 + 打印启动/诊断日志」**，保住克隆即跑 / CI 零依赖底线。执行过程逐行回调 DataWorks 风启动日志（连接信息、开始执行、影响行数、耗时）。**本期不打印 SQL 结果集**（`select * from ...` 的表格结果集留作下一变更，对齐 open-db-studio 多结果集 Tab）。
- **编辑器脏态与按钮语义**（`TaskEditorPane`）：① `loadTask` 补读 `hasDraftChange`；② 任一字段（脚本/类型/配置/参数）变更 → 「保存草稿」呈现「● 待保存」脏态；③ 发布按钮启用条件 = 有已存未发布改动（`hasDraftChange`），且视觉风格与「保存草稿」对齐（同 `variant="outline"`）。
- **运行日志 Tabs 化**：把编辑器底部写死的 `RunLogs` 单面板升级为 Tabs 容器 —— 每次运行开一个 tab，命名 = **任务名 + 运行时间**；tab 内为 DataWorks 风滚屏实时日志（复用现有 SSE）；预留「结果集 tab」位，供后续 SQL 结果集展示落入。

## Capabilities

### New Capabilities
- `sql-task-execution`: SQL 与 ECHO 任务的真实执行器；SQL 按任务绑定的业务数据源连接执行，无可用数据源时回退模拟 + 启动日志（方案 A）；DataWorks 风启动/诊断日志逐行流出；本期不返回结果集（为后续结果集展示预留契约位）。

### Modified Capabilities
- `manual-run-trigger`: `POST /api/tasks/{id}/run` 改为按发布态分流（未发布→TEST，已发布→NORMAL），不再对未发布任务直接 409 拒绝；接口接收可选编辑器临时内容。
- `task-test-run`: 测试运行内容来源由「DB 草稿」放宽为「请求体携带的编辑器当前内容（含未保存）」，不强制先保存。
- `data-development-ide`: 编辑子 Tab 新增脏态可视化（保存草稿→● 待保存）、发布按钮按 `hasDraftChange` 启用并与保存按钮风格对齐；跑后观测由单日志面板升级为 Tabs 容器（任务名+时间命名、DataWorks 风滚屏、预留结果集 tab 位）。

## Impact

- **后端**
  - `dataweave-api`：`TaskController.run`（去 ONLINE 硬卡 + 接收临时内容 + 分流 TEST/NORMAL）、`RunRequest` DTO 扩展；`InProcessTaskExecutionGateway` 执行器分发表注册 SQL/ECHO。
  - `dataweave-master`：`WorkflowTriggerService.triggerTestRun` 扩展接收临时内容；`ActionRequest` 透传内容快照。
  - `dataweave-worker`：新增 `SqlTaskExecutor`（JDBC 连业务数据源 + 方案 A 回退）、`EchoTaskExecutor`；数据源连接获取（读 `datasources` 表）。
  - i18n：错误码/日志文案（后端 `Messages`），保留 `task.not_published` 仅在确无法运行场景。
- **前端**
  - `components/workspace/task-editor-pane.tsx`：脏态、按钮启用/风格、`/run` 携带编辑器内容、`RunLogs`→Tabs 容器。
  - 运行日志 Tab 组件（任务名+时间命名、DataWorks 风、结果集 tab 占位）；next-intl 文案补充（zh-CN/en-US 双 bundle）。
- **数据/契约**：`task_def.datasourceId` 已存在（无需 DDL）；`/run` 请求体向后兼容（新增字段可选）。
- **不变量**：写操作必经闸门、调度死锁防御四不变量、i18n 三规则、DESIGN.md 视觉契约均沿用，不破坏。
