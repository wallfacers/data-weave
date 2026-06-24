## MODIFIED Requirements

### Requirement: 统一运维中心视图

系统 SHALL 在 Workspace 注册视图 `ops`,可经 `dataweave.ui.open`(payload `{view:"ops", params?}`)召唤或经「+」启动器打开,布局为「顶条今日大盘 + 主舞台 Tab + 右栏 Agent 运维举手台」。

运维中心 MUST 以**任务流**为运维主体,任务与任务流概念分离:任务（`TaskDef`）MUST NOT 作为独立可调度/可运维对象出现,只作为任务流的「节点 / 任务实例」被看到。主舞台 Tab 全貌 MUST 为:「周期任务流列表」「手动任务流列表」「任务流实例」「补数据实例」。MUST NOT 再设独立的「手动·测试」Tab;开发态 `TEST_RUN` 测试实例 MUST NOT 出现在运维中心(归数据开发侧自测)。

#### Scenario: 经 ui.open 召唤运维中心
- **WHEN** 后端发出 `CUSTOM(dataweave.ui.open)` payload `{ view: "ops", params: { tab: "instances", filter: { state: "FAILED" } } }`
- **THEN** Workspace 打开/激活 `ops` view,主舞台切到「任务流实例」Tab 且预置失败筛选

#### Scenario: 顶条今日大盘
- **WHEN** ops view 打开
- **THEN** 顶条展示今日 总/运行中/成功/失败 实例数与 SLA 风险数(取 summary + eta-summary),数据为空时显示空态而非报错

#### Scenario: tab 全貌不含测试
- **WHEN** ops view 打开
- **THEN** 主舞台呈现「周期任务流列表/手动任务流列表/任务流实例/补数据实例」四 Tab,无「手动·测试」Tab,无任何 `TEST_RUN` 测试实例

## ADDED Requirements

### Requirement: 运维展示口径仅限已发布对象

运维中心所有列表与下钻 SHALL 仅展示从开发态 `publish` 过来的已发布(`status=ONLINE`)对象及其运行时产物。后端运维查询(任务流列表、实例列表等)MUST 按 `status=ONLINE` 过滤,MUST NOT 返回 `DRAFT` 草稿对象。原 `OpsService.tasks()` 用 `taskDefRepository.findAll()` 无过滤端出草稿的行为 MUST 被纠正。

#### Scenario: 运维列表不含草稿
- **WHEN** 运维中心请求任务流列表,系统中同时存在 DRAFT 与 ONLINE 工作流
- **THEN** 返回结果只含 `status=ONLINE` 的工作流,DRAFT 不出现

#### Scenario: 运维不展示开发态自测实例
- **WHEN** 某 DRAFT 任务在开发侧发起 TEST_RUN,运维中心刷新实例列表
- **THEN** 该 TEST_RUN 实例不出现在运维中心任一 Tab

### Requirement: 周期任务流列表运维面板

系统 SHALL 在「周期任务流列表」Tab 展示 `status=ONLINE & schedule_type=CRON` 的 `WorkflowDef`,列出名称、cron、调度有效期、最近实例状态/ETA,并提供下钻到该任务流实例与发布快照 DAG。冻结/解冻 MUST 为节点级(见 `node-freeze`),面板提供进入 DAG 实例视图冻结节点的入口,MUST NOT 提供任务级 freeze 开关。

#### Scenario: 列出周期任务流
- **WHEN** 用户打开「周期任务流列表」Tab
- **THEN** 仅列出 `ONLINE & CRON` 的工作流,展示 cron 与最近实例状态,可下钻实例

#### Scenario: 看发布快照 DAG
- **WHEN** 用户在某周期任务流上查看 DAG 报告
- **THEN** 展示该工作流**发布快照**(`dag_snapshot_json`)的拓扑,而非 live 草稿 DAG

### Requirement: 手动任务流列表运维面板

系统 SHALL 在「手动任务流列表」Tab 展示 `status=ONLINE & schedule_type=MANUAL` 的 `WorkflowDef`,作为与周期任务流同级的运维对象。面板 MUST 提供「运行一次」动作触发 `POST /api/workflows/{id}/run`,按返回 outcome 分流(EXECUTED/PENDING_APPROVAL/REJECTED),并可下钻其手动来源实例。

#### Scenario: 列出手动任务流
- **WHEN** 用户打开「手动任务流列表」Tab
- **THEN** 仅列出 `ONLINE & MANUAL` 的工作流

#### Scenario: 手动运行一次
- **WHEN** 用户对一条手动任务流点「运行一次」
- **THEN** 系统调 `POST /api/workflows/{id}/run`,按 outcome 分流,成功后在「任务流实例」可见该手动来源实例

### Requirement: 手动触发为实例视图动作非独立 Tab

把任意 `ONLINE` 任务流(含周期任务流)「手动补跑一次」SHALL 是「任务流实例」视图内的**动作**(重跑/补跑入口)与补数据流,MUST NOT 设独立 Tab。手动触发产出的实例 MUST 在「任务流实例」视图按来源(`runMode`)筛选可见,而非回流到「手动任务流列表」(后者只按「定义为 MANUAL」列对象)。

#### Scenario: 手动补跑周期任务流
- **WHEN** 用户在「任务流实例」视图对某周期任务流发起手动补跑
- **THEN** 产出一条手动来源实例,可在实例视图按来源筛出;「手动任务流列表」不因此新增条目

#### Scenario: 手动来源与对象口径分离
- **WHEN** 用户在「手动任务流列表」查看
- **THEN** 仅见 `schedule_type=MANUAL` 的工作流对象,不含被手动补跑的 CRON 工作流
