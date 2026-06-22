## Context

任务的「编辑↔运行」当前被「发布」隔成互斥两态：`TaskService.update` 仅允许 `DRAFT` 编辑，`TaskController.run`（行 147）硬卡 `ONLINE`，未发布即抛 `BizException("task.not_published")` 409。但底层其实已为「草稿测试运行」铺好地基：

- `WorkflowTriggerService.triggerTestRun(taskId, bizDate)` 已存在，设 `run_mode=TEST`、跑草稿（version=null）、不计正式统计；
- `SchedulerKernel` 已为 TEST 预留专属槽 + 最高优先（`selectRunnable(true)` 先认领）；
- `TaskDef` 已有 `datasourceId`/`targetDatasourceId` 字段，`datasources`/`datasource_types` 表齐全；
- `ShellTaskExecutor` 已是真实执行器，逐行 `onLine` 回调 → `LogBus` → SSE；
- 前端 `RunLogs`（task-editor-pane.tsx:466）已消费 `/logs/stream`，但写死单面板。

缺口集中在：① `run` 接口不分流、不接编辑器内容；② SQL/ECHO 无真实执行器（all-in-one 模拟、零日志）；③ 编辑器无脏态、发布按钮风格/启用不对；④ 日志单面板、命名不可读。

约束：写操作必经 `GatedActionService` 闸门（CLAUDE.md 硬规矩，无绕过）；调度死锁防御四不变量；i18n 三规则；DESIGN.md 视觉契约；克隆即跑 / CI 零外部依赖底线（H2/all-in-one）。

## Goals / Non-Goals

**Goals:**
- 解绑「发布」与「运行」：未发布→TEST、已发布→NORMAL，二者均经闸门。
- 测试运行用编辑器**当前内容（含未保存）**，不强制先落库。
- SQL/ECHO 真实执行（方案 A：有可用数据源才真跑，否则回退模拟+启动日志）。
- 编辑器脏态可视化 + 发布按钮按 `hasDraftChange` 启用且风格对齐。
- 运行日志 Tabs 化（任务名+时间命名、DataWorks 风滚屏、预留结果集 tab 位）。

**Non-Goals:**
- SQL 结果集渲染（`select *` 表格、open-db-studio 多结果集 Tab）—— 留作下一变更，本期仅预留 tab 契约位。
- 数据源连接池/凭据管理体系化（本期按需建连即可，复用现有 `datasources` 表与密码解密）。
- 工作流（workflow）的测试运行 —— 本期只动单任务 `/api/tasks/{id}/run`。
- 新增 `trigger_type` 列 —— 沿用 `run_mode` 区分。

## Decisions

### D1：`run` 接口按发布态分流，而非二选一新端点
`POST /api/tasks/{id}/run` 内部依 `task.status` 分流：`ONLINE`→`triggerManualTaskRun`（NORMAL），否则→`triggerTestRun`（TEST）。
- **为何**：前端只有一个「运行」按钮，语义对用户是「跑一下」；发布态对运行模式的影响应由后端裁定，避免前端硬编码两条调用路径。保留单端点也让 `manual-run-trigger` spec 收敛在一处。
- **替代**：新增 `/api/tasks/{id}/test-run`。否决——前端要按状态切端点，且与既有「运行」按钮语义重复。

### D2：测试运行内容经请求体透传，落 `task_instance.content_override`，不写 `task_def`
`RunRequest` 扩展为 `{ bizDate, content?, type?, paramsJson? }`。TEST 分支把临时内容传入 `triggerTestRun`，写入**实例级**新列 `task_instance.content_override`（TEXT，nullable），下发实例 `task_version_no=null`，**不写 `task_def`**。已发布 NORMAL 分支忽略请求体内容，永远跑发布版本快照。
- **内容如何到达执行器**：`SchedulerKernel.contentOf` 在 claim 时解析内容——原逻辑 `taskVersionNo!=null → task_def_version` 否则 `task_def`。新增**最高优先级**：`content_override` 非空则直接用它（仅 TEST 实例会有）。这样临时内容跨 master 持久、走既有占位符解析与下发链，无需碰 `task_def`。
- **为何加列而非内存透传**：内容在 claim 时才解析，触发与执行解耦（可能不同 master）。内存 map 在分布式/重启下丢失。实例级列既持久又天然随实例生命周期回收，且不污染 task_def 草稿态。
- **为何**：用户明确要「不管存没存，跑编辑器最新内容」。而 `TaskService.update` 只允许 DRAFT 编辑——若任务是 ONLINE，根本无法靠「先存草稿」承载未保存内容。请求体透传是唯一干净解。临时内容随 `ActionRequest` 命令快照入 `agent_action`，满足「跑了什么可回放」。
- **替代**：前端先静默 `PUT` 存草稿再 run。否决——ONLINE 任务存不了草稿；且会污染草稿态、与「测试不落库」语义冲突。

### D3：SQL/ECHO 执行器注册进 `InProcessTaskExecutionGateway.byType`，方案 A 回退在执行器内部
新增 `SqlTaskExecutor`、`EchoTaskExecutor`，注册到 type→executor 映射。SqlTaskExecutor 内部：解析 `datasourceId`→建 JDBC 连接→执行；**无可用数据源/无驱动/连接失败 → 回退「模拟成功 + 启动日志标注」**，绝不抛错中断调度。这样删掉「无执行器即模拟」的兜底分支后，模拟逻辑收敛进 SqlTaskExecutor 自身的回退路径，语义更清晰。
- **为何**：方案 A 要求「有库才真跑、无库不破坏零依赖」。把回退判定放执行器内部（而非 gateway 的 `executor==null`），让「SQL 已接执行器但当前环境无库」可表达。
- **替代**：方案 B（种子预置数据源开箱真跑）。否决——H2 模式仍要降级，且强行连 docker PG 与「克隆即跑」冲突。方案 C（SQL 先不真跑）。否决——用户明确要 SQL 也真跑。

### D4：数据源连接获取最小化
SqlTaskExecutor 读 `datasources` 行 → 用 `jdbcUrl`+`username`+解密 `passwordEnc` 经 `DriverManager`/单次连接执行，try-with-resources 即用即关，本期不引连接池。密码解密复用平台既有解密路径（与 open-db-studio AES-256-GCM 不同体系，按 data-weave 现有 `passwordEnc` 约定）。
- **为何**：本期无高频/并发执行压力，最小实现先打通闭环；连接池/凭据体系化列入 Non-Goals 后续演进。
- **风险见下**：驱动 classpath。

### D5：前端日志 Tabs 容器复用内层 TabStrip 风格
把 `task-editor-pane.tsx` 底部写死的 `RunLogs` 升级为一个轻量 Tabs 容器：state 维护 `runTabs: {instanceId, taskName, startedAt, kind:'log'|'result'}[]`，每次 `handleRun` 成功 push 一个 `kind:'log'` tab（命名 `${taskName} · ${formatDateTime(startedAt, locale)}`），复用 DataWorks 风样式（启动行高亮、等宽、自动滚底）。结果集 tab（`kind:'result'`）本期不渲染但类型/容器预留。
- **为何**：data-development-ide 已规定子 Tab 复用 `TabStrip` 风格，日志 Tabs 沿用同一视觉语言；命名走 `formatDateTime(iso, locale)`（i18n 规则）。
- **替代**：复用底部 `WorkspaceLogPanel`（多实例多 tab，命名 `任务名·shortId`）。否决——它是跨工作区的全局浮层，与「编辑器内就地观测」语义不同；且命名口径要改。两者可并存（编辑器内 tab 观测 + 全局浮层留底）。

### D6：DataWorks 风启动日志在执行器层注入
启动/收尾诊断行（run_mode、type、datasource、开始/结束/耗时、状态）由各 `TaskExecutor` 在 `doExecute` 首尾经 `onLine` 写入，与脚本输出同一日志流有序呈现。后端文案经 `Messages.get`（按 agent locale，i18n 规则②）。
- **为何**：诊断信息属「后端动态生成」文案，归后端 Messages；放执行器层保证 TEST/NORMAL、三种类型一致包裹。

## Risks / Trade-offs

- **[JDBC 驱动 classpath]** SqlTaskExecutor 真跑需目标库驱动在 worker classpath；缺驱动会连不上 → 命中方案 A 回退（日志标注「无可用驱动，模拟执行」），不崩溃。本期只保证 PostgreSQL 驱动（平台自带）可用，其余库驱动列为后续。
- **[未保存内容经网络透传]** TEST 把编辑器全文发到后端并入 `agent_action`。审计留痕是预期收益；但大脚本会增大请求体/审计行——可接受（与日志同量级）。
- **[闸门对 TEST 默认放行]** 默认 L1 直执行符合现有 `task-test-run` spec；企业可经 `policy_rules` 抬级。需确保分流到 TEST 也构造 `ActionRequest`，不能因「测试」绕过闸门。
- **[回退模拟的语义模糊]** SQL 在无库环境「成功」可能误导用户以为真跑了 → 必须在日志显式标注「模拟执行」，且实例状态语义保持 SUCCESS（调度闭环需要）。文案要醒目。
- **[H2/PG 方言]** 涉及读 `datasources` 的 SQL 用 CONCAT 不用 `||`、DDL `IF NOT EXISTS`，两库各测（见 memory h2-pg-sql-dialect-traps）。本期无新表，风险低。
- **[前端 dirty 误报]** 初始 `loadTask` 的 setState 不能触发 dirty；需以「加载完成后的首个用户编辑」为脏态起点（参考 workflow-canvas 的 dirty 实现）。

## Migration Plan

1. 后端先行：扩展 `RunRequest` + `TaskController.run` 分流（向后兼容，旧请求体 `{bizDate}` 仍工作）。
2. `triggerTestRun` 接收可选临时内容（默认回退 DB 草稿，保证既有 rerun 路径不变）。
3. 新增 SQL/ECHO 执行器并注册；删除 gateway `executor==null` 兜底（语义迁入执行器回退）。`./mvnw install -DskipTests` 后跑。
4. 前端：脏态 + 按钮 + `/run` 携带内容 + 日志 Tabs，逐步替换 `RunLogs`。
5. i18n 双 bundle 补 key（zh-CN/en-US 键集一致）。
6. **回滚**：纯增量；如需回退，`run` 分流改回「非 ONLINE 即 409」、执行器映射移除 SQL/ECHO 即恢复旧行为，无数据迁移。

## Open Questions

- SQL 真跑的「成功」判定：DML（影响行数）与 DDL（无返回）与 `SELECT`（本期不取结果集）统一按「未抛异常即 SUCCESS」？倾向是。
- ECHO 是否独立 `type=ECHO`，还是并入 SHELL 的 `echo` 用法？倾向独立 type（spec 已按独立 ECHO 写），便于「最轻量验证闭环」。apply 时若发现现有种子无 ECHO type，可顺带加一个示例任务。
