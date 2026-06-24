## ADDED Requirements

### Requirement: 运行按钮单按钮 Run⇄Stop 状态机

数据开发的任务编辑器与工作流画布的「运行」入口 SHALL 以**单个按钮**呈现 Run⇄Stop 切换,其显示态 MUST 由**当前运行实例的真实执行态**驱动,而非 `POST /run` 请求的往返时长:

- 无当前运行实例,或当前运行实例处**终态**(`SUCCESS`/`FAILED`/`STOPPED`)→ 按钮显示「运行」(任务编辑器按发布态区分「运行」/「试跑」文案),图标 MUST 为 `PlayIcon`。
- 当前运行实例处**非终态**(`WAITING`/`DISPATCHED`/`RUNNING`,以及 SSE 尚在连接中的过渡态)→ 同一按钮显示「停止」,图标 MUST 为 `StopIcon`。
- 「停止」操作 MUST 终止**当前运行实例**(任务走 `POST /api/ops/task-instances/{id}/kill`、工作流走 `POST /api/ops/instances/{id}/kill`),而非「当前激活的日志 Tab」对应实例。
- 运行按钮态的真相源 MUST 与日志 Tab 圆点(`deriveRunDotState`)同源(由 `RunLogsTabs` 上报的圆点态聚合得到),不另开独立 SSE 连接。
- 工作流画布的「发布」按钮 MUST 保持 `RocketIcon` 不变,不受运行按钮换图标影响。

#### Scenario: 运行中按钮变停止

- **WHEN** 用户点击「运行」,后端返回 `EXECUTED` 且实例进入 RUNNING
- **THEN** 该按钮变为「停止」并显示 `StopIcon`,直至实例到达终态

#### Scenario: 终态后按钮复位

- **WHEN** 当前运行实例到达 `SUCCESS`/`FAILED`/`STOPPED`
- **THEN** 按钮复位为「运行/试跑」并显示 `PlayIcon`

#### Scenario: 停止终止的是当前运行实例

- **WHEN** 用户在打开多个历史日志 Tab 的情况下点击「停止」
- **THEN** 系统终止的是本次触发(或续接到)的当前运行实例,而非当前激活查看的那个历史 Tab

#### Scenario: 短任务运行态一闪即逝不误判

- **WHEN** 一个瞬时完成的任务被触发
- **THEN** 按钮在收到终态前后正确呈现,不会停留在「运行中」也不会因 POST 返回即误判为可再次运行

### Requirement: 重开或刷新后运行态续接

任务编辑子 Tab 与工作流画布子 Tab 在**挂载时** SHALL 向后端查询该任务/工作流的**最近活跃实例**,并据其真实态续接前端运行态——使关闭重开子 Tab 或刷新页面后,长耗时运行的「停止」入口与日志流不丢失:

- 任务编辑器挂载 MUST 调 `GET /api/ops/tasks/{taskDefId}/latest-instance`;若返回实例处非终态,MUST 将其设为当前运行实例、续开日志 Tab 接 `logs/stream`、按钮显示「停止」。
- 工作流画布挂载 MUST 调 `GET /api/ops/workflows/{workflowId}/latest-instance`;若返回实例处非终态,MUST 续订阅该实例的 `events/stream`、拉实例详情重建「实例 UUID↔task_def id」映射、按真实态给节点重新着色、整体运行徽标与停止按钮恢复。
- 若最近实例处终态或不存在,按钮 MUST 保持「运行」,且 MUST NOT 自动回显历史日志 Tab。
- 续接 MUST 自愈于竞态:若查询返回非终态但实例随即跑完,`logs/stream` 的「历史回放 + end 事件」机制 MUST 使按钮在收到终态后自动复位。

#### Scenario: 刷新后长任务仍可停止

- **WHEN** 一个长耗时任务正在运行,用户刷新页面并重开该任务编辑器
- **THEN** 按钮显示「停止」,日志 Tab 续上实时日志流,而非显示「可运行、无日志」

#### Scenario: 重开终态任务不误显停止

- **WHEN** 用户重开一个最近一次运行已成功结束的任务
- **THEN** 按钮显示「运行/试跑」,不自动弹出上次日志 Tab

#### Scenario: 工作流刷新后节点态与停止续接

- **WHEN** 一个工作流实例运行中,用户刷新并重开其画布
- **THEN** 画布重新订阅事件流,运行中节点恢复着色,整体运行徽标与「停止」按钮恢复

#### Scenario: 续接竞态自愈

- **WHEN** 挂载查询返回非终态实例,但 SSE 连上时实例已结束
- **THEN** 前端经历史回放与 end 事件收到终态,按钮自动复位为「运行」,不卡在「停止」

### Requirement: 最近活跃实例查询端点

系统 SHALL 提供按定义查最近一次实例的查询端点,供前端续接运行态;实现 MUST 利用 `id`(UUIDv7 时间有序)降序取最新一条,MUST NOT 依赖墙钟时间排序:

- `GET /api/ops/tasks/{taskDefId}/latest-instance?runMode=NORMAL|TEST`:返回该任务定义按 `run_mode` 过滤后的最近一个 task_instance 的 `{id, state, runMode}`;无则返回空数据。`runMode` 省略时默认 `NORMAL`。
- `GET /api/ops/workflows/{workflowId}/latest-instance`:返回该工作流定义最近一个 workflow_instance 的 `{id, state}`;无则返回空数据。
- 两端点 MUST 经既有鉴权(JWT),响应 MUST 遵循统一 `ApiResponse`(`code`/`data`)契约。
- 仅为查询,MUST NOT 触发任何写副作用或影响调度计划。

#### Scenario: 查任务最近正式实例

- **WHEN** 调 `GET /api/ops/tasks/{taskDefId}/latest-instance`(默认 NORMAL)
- **THEN** 返回该任务最近一个 NORMAL 实例的 id 与 state;无 NORMAL 实例时返回空数据

#### Scenario: 按 runMode 过滤试跑实例

- **WHEN** 调 `GET /api/ops/tasks/{taskDefId}/latest-instance?runMode=TEST`
- **THEN** 仅在 TEST 实例中取最新返回,不串入 NORMAL 实例

#### Scenario: 查工作流最近实例

- **WHEN** 调 `GET /api/ops/workflows/{workflowId}/latest-instance`
- **THEN** 返回该工作流最近一个实例的 id 与 state;无则返回空数据

#### Scenario: 未鉴权拒绝

- **WHEN** 不带 JWT 调用上述端点
- **THEN** 返回 401,不泄露实例数据
