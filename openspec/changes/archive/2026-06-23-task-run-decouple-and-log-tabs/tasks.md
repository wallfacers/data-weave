# Tasks

## 1. 后端：run 接口解绑与内容透传

- [x] 1.1 扩展 `RunRequest` DTO 为 `{ bizDate, content?, type?, paramsJson? }`（新增字段全部可选，向后兼容旧 `{bizDate}`）
- [x] 1.2 改 `TaskController.run`：去掉 `!"ONLINE" → 409 task.not_published` 硬卡，改为按 `task.status` 分流（ONLINE→NORMAL 路径，否则→TEST 路径）
- [x] 1.3 两条分流均构造 `ActionRequest` 经 `GatedActionService.submit`（TEST 也必须经闸门，不绕过）；`ActionRequest` 命令快照携带本次下发的临时内容，便于 `agent_action` 回放
- [x] 1.4 DDL：`task_instance` 增 `content_override TEXT`（nullable）列（schema.sql，H2/PG 兼容）；`TaskInstance` 域 + 字段 getter/setter
- [x] 1.4b `WorkflowTriggerService.triggerTestRun` 扩展为接收可选临时内容（content/type/paramsJson）：有则写入 `content_override`、无则回退 DB 草稿；下发实例 `task_version_no=null`，不写 `task_def`
- [x] 1.4c `SchedulerKernel.contentOf` 优先用 `content_override`（非空时）；`selectRunnable` SQL 取该列；`paramsJsonOf` 同理支持 override 的 paramsJson
- [x] 1.5 确认 NORMAL 分支仍跑发布版本快照、计入正式统计，行为不变（回归 `manual-run-trigger` 既有场景）
- [x] 1.6 i18n：保留 `task.not_published` 仅用于确无法运行场景；新增/调整后端 `Messages` 文案（zh/en properties）
- [x] 1.7 `./mvnw -q -pl dataweave-api -am compile` 确认零编译错误

## 2. 后端：SQL / ECHO 真实执行器（方案 A）

- [x] 2.1 新增 `EchoTaskExecutor`（dataweave-worker）：内容逐行经 `onLine` 回显、立即成功；注册 `type=ECHO`
- [x] 2.2 新增 `SqlTaskExecutor`：读 `datasources` 行（按 `task.datasourceId`）→ 解密 `passwordEnc` → `DriverManager` 建连（try-with-resources 即用即关）→ 执行脚本
- [x] 2.3 SqlTaskExecutor 方案 A 回退：无 `datasourceId`/数据源不存在/无驱动/连接失败 → 「模拟成功 + 日志显式标注（未配置可用数据源/连接失败原因，模拟执行）」，绝不抛错中断调度
- [x] 2.4 SqlTaskExecutor 本期 **不打印结果集**：仅输出诊断（连接、开始、影响/返回行数摘要、耗时）；统一「未抛异常即 SUCCESS」判定（DML/DDL/SELECT）
- [x] 2.5 在 `InProcessTaskExecutionGateway` 注册 SQL/ECHO 进 `byType`；移除 `executor==null` 兜底模拟分支（语义迁入 SqlTaskExecutor 回退）
- [x] 2.6 DataWorks 风启动/收尾日志：各 `TaskExecutor` 首尾经 `onLine` 注入 run_mode/type/datasource/开始-结束-耗时/状态行；文案经后端 `Messages`（按 agent locale）
- [x] 2.7 `./mvnw install -DskipTests` 后 `-pl dataweave-worker compile` 确认零编译错误

## 3. 前端：编辑器脏态与按钮语义

- [x] 3.1 `task-editor-pane.tsx` `loadTask` 补读 `hasDraftChange`（TaskDef 类型补字段）
- [x] 3.2 加 dirty 追踪：以「加载完成后首个用户编辑」为脏态起点（避免初始化 setState 误报，参考 workflow-canvas 实现）
- [x] 3.3 「保存草稿」按钮脏态视觉：dirty 时呈现「● 待保存」；保存成功后清脏态
- [x] 3.4 发布按钮：启用条件 = `hasDraftChange`（无未发布改动禁用）；`variant` 改为与「保存草稿」一致（outline），去掉 secondary 区分
- [x] 3.5 `pnpm typecheck` 确认零类型错误

## 4. 前端：运行触发携带内容 + 日志 Tabs 化

- [x] 4.1 `handleRun` 的 `/run` 请求体携带编辑器当前内容 `{ content, type, paramsJson }`（支持未保存即测试运行）
- [x] 4.2 用 `runTabs` state 替换写死的 `RunLogs` 单面板：每次运行成功 push 一个 `{instanceId, taskName, startedAt, kind:'log'}`，tab 命名 `${taskName} · ${formatDateTime(startedAt, locale)}`
- [x] 4.3 日志 tab 内复用 SSE（`/api/ops/instances/{id}/logs/stream`）实时滚屏，DataWorks 风样式（启动行高亮、等宽、自动滚底、手动滚动暂停自动滚）
- [x] 4.4 多次运行日志 tab 并存可切换、可关闭，不互相覆盖；Tabs 容器结构预留 `kind:'result'` 结果集 tab 位（本期不渲染）
- [x] 4.5 i18n：新增 tab/日志相关 key 到 zh-CN/en-US 双 bundle（键集一致），命名走 `formatDateTime(iso, locale)`
- [x] 4.6 `pnpm typecheck` 确认零类型错误

## 5. 测试与验证

- [x] 5.1 后端：`TaskControllerRunTest`（未发布→TEST_RUN+内容编码 / 已发布→TASK_RUN）；`ManualRunTriggerTest.runTask_draft_runsAsTestInstance_notRejected` 全栈 HTTP 验证草稿不再 409 而起 TEST 实例；`TestRunCommandTest` 编解码往返
- [x] 5.2 后端：`SqlTaskExecutorTest`（无数据源→模拟+标注 / 不可达数据源→回退不抛错 / 真 H2 执行报告影响行数且不打印结果集）；`EchoTaskExecutorTest` 真回显
- [~] 5.3 前端：仓库无 React 组件测试 harness（现有 vitest 仅纯逻辑）；dirty/发布逻辑改由 `pnpm typecheck` + `i18n:lint` 覆盖（均通过），组件测试性价比低，略
- [x] 5.4 Browser Verification Gate：**已执行**（重启新构建后端 h2 + playwright 实跑）。验证：草稿→「Test run」不再 409；已发布→「Run」；改字段→「● Unsaved」脏态；运行→日志 tab「任务名·时间」命名；SQL 方案 A 回退（`No suitable driver→模拟执行`）+ DataWorks banner 在浏览器正常渲染；console 无非-Monaco 错误（Monaco CDN 被墙是环境问题）。
  - **浏览器验证中发现并修复 4 个缺陷**：① `LogTab` onConn 内联回调致 effect 死循环（Maximum update depth）→ ref 持有 + effect 仅依赖 state + setConn 幂等；② `handleRun` 未传 bizDate → 日期占位符 `${yyyymmdd}` 解析失败 → 补传 previewBizDate；③ **all-in-one 已结束实例日志流只读 logArchive（从不写入）→ 恒空「无日志记录」** → `OpsController.streamEndedLogs` 改为 LogBus→归档→`task_instance.log` 三级回退 + `OpsService.findInstance`（含 TEST）；④ 收到 `end` 不关闭 EventSource → 自动重连重发全量日志刷屏 → `useEventSource` 收 end 即 `close()`
- [x] 5.5 H2 回归：master 118 + worker 33 + api 147（h2）全绿；新增 schema 列随 schema.sql 在 H2 加载通过。2 个 PG-only 测试（`AguiEndpointTest`/`HealthAndCorsTest`）因本环境无 Docker PG 超时——**预存在、与本变更无关**
